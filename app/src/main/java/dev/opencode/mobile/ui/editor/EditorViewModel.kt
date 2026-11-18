package dev.opencode.mobile.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.net.FileWriter
import dev.opencode.mobile.data.net.GitChange
import dev.opencode.mobile.data.net.GitResult
import dev.opencode.mobile.data.net.GitRunner
import dev.opencode.mobile.data.net.LoadedFile
import dev.opencode.mobile.data.net.ReadResult
import dev.opencode.mobile.data.net.SaveResult
import dev.opencode.mobile.data.net.ServerConfig
import dev.opencode.mobile.ui.common.DiffLine
import dev.opencode.mobile.ui.common.DiffLines
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EditorLanguage(val id: String, val label: String) {
    TYPESCRIPT("typescript", "TypeScript"),
    TSX("tsx", "TSX"),
    JAVASCRIPT("javascript", "JavaScript"),
    JSX("jsx", "JSX"),
    JSON("json", "JSON"),
    KOTLIN("kotlin", "Kotlin"),
    MARKDOWN("markdown", "Markdown"),
    CSS("css", "CSS"),
    YAML("yaml", "YAML"),
    XML("xml", "XML"),
    PLAIN("plain", "Plain text");

    companion object {
        fun forPath(path: String): EditorLanguage {
            val name = path.substringAfterLast('/').lowercase()
            return when (name.substringAfterLast('.', "")) {
                "ts", "mts", "cts" -> TYPESCRIPT
                "tsx" -> TSX
                "js", "mjs", "cjs" -> JAVASCRIPT
                "jsx" -> JSX
                "json", "jsonc", "json5" -> JSON
                "kt", "kts" -> KOTLIN // includes build.gradle.kts / settings.gradle.kts
                "md", "markdown", "mdx" -> MARKDOWN
                "css", "scss", "less" -> CSS
                "yml", "yaml" -> YAML
                "xml", "svg", "plist" -> XML
                else -> PLAIN
            }
        }
    }
}

enum class SaveStatus { IDLE, SAVING, SAVED, FAILED }

data class EditorTab(
    val path: String,
    val language: EditorLanguage,
    val dirty: Boolean = false,
    val status: SaveStatus = SaveStatus.IDLE,
    val message: String? = null,
) {
    val name: String get() = path.substringAfterLast('/')
}

/** The file changed on the Mac since it was opened; the save was refused. */
data class SaveConflict(
    val path: String,
    val local: String,
    val remote: LoadedFile?,
    val showDiff: Boolean = false,
) {
    val diff: List<DiffLine> by lazy { DiffLines.compute(remote?.text.orEmpty(), local) }
}

data class EditorUi(
    val tabs: List<EditorTab> = emptyList(),
    val active: String? = null,
    val loading: String? = null,
    val error: String? = null,
    val cursor: Pair<Int, Int>? = null,
    val conflict: SaveConflict? = null,
    val branch: String? = null,
    /** `git status` entries, or null when the folder isn't a repository (or git couldn't run). */
    val changes: List<GitChange>? = null,
    val softWrap: Boolean = false,
    val paletteOpen: Boolean = false,
    /** A tab whose close is waiting on "discard unsaved changes?". */
    val pendingClose: String? = null,
    /** CodeMirror has focus — the soft keyboard (if up) is typing into the editor. */
    val editorFocused: Boolean = false,
) {
    val activeTab: EditorTab? get() = tabs.firstOrNull { it.path == active }
    val dirtyTabs: List<EditorTab> get() = tabs.filter { it.dirty }
}

/** What the WebView should do next; delivered in order, buffered while the page is (re)loading. */
sealed interface EditorCommand {
    data class Load(val path: String, val text: String, val language: EditorLanguage, val line: Int, val savedText: String?) : EditorCommand
    data class Show(val path: String, val line: Int) : EditorCommand
    data class Close(val path: String) : EditorCommand
    data class GetText(val id: Int, val path: String) : EditorCommand
    data class Saved(val id: Int, val path: String) : EditorCommand
    data class Insert(val text: String) : EditorCommand
    data class Run(val name: String) : EditorCommand
    data class Wrap(val on: Boolean) : EditorCommand
}

/**
 * Editor state for one server. Activity-scoped (see [rememberEditorViewModel]) so the Code tab, the
 * editor route and the tablet layout share the same open tabs.
 *
 * The WebView is disposable (it goes away whenever its screen leaves composition), so the ViewModel
 * keeps what's needed to rebuild it: the on-disk text and hash of every tab, plus the latest unsaved
 * snapshot the page reported.
 */
class EditorViewModel(
    private val app: OpenCodeApp,
    private val server: ServerConfig,
    private val projectDir: () -> String?,
) : ViewModel() {

    private val api = app.repository.apiFor(server)
    private val writer = FileWriter(server, api)
    private val git = GitRunner(server, api)

    private val _ui = MutableStateFlow(EditorUi())
    val ui: StateFlow<EditorUi> = _ui

    private val commandChannel = Channel<EditorCommand>(Channel.UNLIMITED)
    val commands: Flow<EditorCommand> = commandChannel.receiveAsFlow()

    private val savedText = HashMap<String, String>()
    private val baseHash = HashMap<String, String>()
    private val snapshots = HashMap<String, String>()
    private val pendingSaves = HashMap<Int, String>()
    private var nextRequest = 1

    val recentFiles: Flow<List<String>> get() = app.serverStore.recentFiles(server.id, projectDir())

    init {
        viewModelScope.launch {
            val wrap = app.serverStore.editorSoftWrap.first()
            _ui.update { it.copy(softWrap = wrap) }
        }
        refreshGit()
    }

    private fun send(command: EditorCommand) {
        commandChannel.trySend(command)
    }

    /** The page (re)started: drop stale queued commands and rebuild every tab from our copies. */
    fun onEditorReady() {
        while (commandChannel.tryReceive().isSuccess) Unit
        val state = _ui.value
        send(EditorCommand.Wrap(state.softWrap))
        for (tab in state.tabs) {
            val saved = savedText[tab.path] ?: continue
            val snapshot = snapshots[tab.path]
            send(EditorCommand.Load(tab.path, snapshot ?: saved, tab.language, 0, if (snapshot != null) saved else null))
        }
        state.active?.let { send(EditorCommand.Show(it, 0)) }
        // A save in flight when the page went away can't be answered any more.
        if (pendingSaves.isNotEmpty()) {
            pendingSaves.clear()
            _ui.update { ui -> ui.copy(tabs = ui.tabs.map { if (it.status == SaveStatus.SAVING) it.copy(status = SaveStatus.IDLE) else it }) }
        }
    }

    fun open(path: String, line: Int? = null) {
        val target = line ?: 0
        val existing = _ui.value.tabs.firstOrNull { it.path == path || path.endsWith("/${it.path}") }
        if (existing != null) {
            select(existing.path, target)
            return
        }
        _ui.update { it.copy(loading = path, error = null) }
        viewModelScope.launch {
            when (val result = writer.read(path, projectDir())) {
                is ReadResult.Ok -> {
                    val file = result.file
                    savedText[file.path] = file.text
                    baseHash[file.path] = file.hash
                    snapshots.remove(file.path)
                    val language = EditorLanguage.forPath(file.path)
                    _ui.update { ui ->
                        val tabs = if (ui.tabs.any { it.path == file.path }) ui.tabs else ui.tabs + EditorTab(file.path, language)
                        ui.copy(tabs = tabs, active = file.path, loading = null)
                    }
                    send(EditorCommand.Load(file.path, file.text, language, target, null))
                    app.serverStore.addRecentFile(server.id, projectDir(), file.path)
                }
                is ReadResult.Failed -> _ui.update { it.copy(loading = null, error = "${path.substringAfterLast('/')}: ${result.reason}") }
            }
        }
    }

    fun select(path: String, line: Int = 0) {
        _ui.update { it.copy(active = path) }
        send(EditorCommand.Show(path, line))
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    fun requestClose(path: String) {
        val tab = _ui.value.tabs.firstOrNull { it.path == path } ?: return
        if (tab.dirty) _ui.update { it.copy(pendingClose = path) } else close(path)
    }

    fun cancelClose() = _ui.update { it.copy(pendingClose = null) }

    fun close(path: String) {
        savedText.remove(path)
        baseHash.remove(path)
        snapshots.remove(path)
        send(EditorCommand.Close(path))
        _ui.update { ui ->
            val index = ui.tabs.indexOfFirst { it.path == path }
            val tabs = ui.tabs.filterNot { it.path == path }
            val active = if (ui.active == path) tabs.getOrNull((index - 1).coerceAtLeast(0))?.path else ui.active
            ui.copy(tabs = tabs, active = active, pendingClose = null)
        }
        _ui.value.active?.let { send(EditorCommand.Show(it, 0)) }
    }

    fun closeActive() {
        _ui.value.active?.let { requestClose(it) }
    }

    /** Back with unsaved tabs → "Discard & leave": drop every dirty tab. */
    fun discardDirty() {
        _ui.value.dirtyTabs.forEach { close(it.path) }
    }

    // ---- bridge callbacks (called on the WebView's JS thread; StateFlow updates are thread-safe) ----

    fun onChange(path: String, dirty: Boolean) {
        if (!dirty) snapshots.remove(path)
        _ui.update { ui ->
            ui.copy(tabs = ui.tabs.map { tab ->
                if (tab.path != path || tab.dirty == dirty) tab
                else tab.copy(dirty = dirty, status = if (dirty && tab.status == SaveStatus.SAVED) SaveStatus.IDLE else tab.status)
            })
        }
    }

    fun onSnapshot(path: String, text: String) {
        if (savedText.containsKey(path)) snapshots[path] = text
    }

    fun onFocus(focused: Boolean) = _ui.update { it.copy(editorFocused = focused) }

    fun onCursor(line: Int, col: Int) = _ui.update { it.copy(cursor = line to col) }

    fun onText(id: Int, path: String, text: String) {
        val expected = pendingSaves.remove(id) ?: return
        if (expected != path) return
        performSave(id, path, text, force = false)
    }

    // ---- saving ----

    fun save(path: String? = _ui.value.active) {
        val target = path ?: return
        val tab = _ui.value.tabs.firstOrNull { it.path == target } ?: return
        if (tab.status == SaveStatus.SAVING) return
        val id = nextRequest++
        pendingSaves[id] = target
        setStatus(target, SaveStatus.SAVING, null)
        send(EditorCommand.GetText(id, target))
    }

    private fun performSave(id: Int, path: String, text: String, force: Boolean) {
        setStatus(path, SaveStatus.SAVING, null)
        viewModelScope.launch {
            when (val result = writer.save(path, text, baseHash[path], projectDir(), force)) {
                is SaveResult.Saved -> {
                    baseHash[path] = result.hash
                    savedText[path] = text
                    if (snapshots[path] == text) snapshots.remove(path)
                    send(EditorCommand.Saved(id, path))
                    setStatus(path, SaveStatus.SAVED, null)
                    refreshGit()
                }
                is SaveResult.Conflict -> {
                    setStatus(path, SaveStatus.IDLE, "File changed on the Mac")
                    _ui.update { it.copy(conflict = SaveConflict(path, text, result.remote)) }
                    pendingConflictId = id
                }
                is SaveResult.Failed -> setStatus(path, SaveStatus.FAILED, result.reason)
            }
        }
    }

    private var pendingConflictId = 0

    fun conflictShowDiff() = _ui.update { ui -> ui.copy(conflict = ui.conflict?.copy(showDiff = true)) }

    fun conflictDismiss() = _ui.update { it.copy(conflict = null) }

    fun conflictOverwrite() {
        val conflict = _ui.value.conflict ?: return
        _ui.update { it.copy(conflict = null) }
        performSave(pendingConflictId, conflict.path, conflict.local, force = true)
    }

    fun conflictReload() {
        val conflict = _ui.value.conflict ?: return
        _ui.update { it.copy(conflict = null) }
        val remote = conflict.remote
        if (remote == null) {
            _ui.update { it.copy(error = "${conflict.path} no longer exists on the Mac") }
            return
        }
        savedText[conflict.path] = remote.text
        baseHash[conflict.path] = remote.hash
        snapshots.remove(conflict.path)
        val language = EditorLanguage.forPath(conflict.path)
        send(EditorCommand.Load(conflict.path, remote.text, language, _ui.value.cursor?.first ?: 0, null))
        _ui.update { ui -> ui.copy(tabs = ui.tabs.map { if (it.path == conflict.path) it.copy(dirty = false, status = SaveStatus.IDLE, message = null) else it }) }
    }

    private fun setStatus(path: String, status: SaveStatus, message: String?) {
        _ui.update { ui -> ui.copy(tabs = ui.tabs.map { if (it.path == path) it.copy(status = status, message = message) else it }) }
    }

    // ---- editor commands ----

    fun insert(text: String) = send(EditorCommand.Insert(text))

    fun command(name: String) = send(EditorCommand.Run(name))

    fun toggleWrap() {
        val on = !_ui.value.softWrap
        _ui.update { it.copy(softWrap = on) }
        send(EditorCommand.Wrap(on))
        viewModelScope.launch { app.serverStore.setEditorSoftWrap(on) }
    }

    fun showPalette(open: Boolean) = _ui.update { it.copy(paletteOpen = open) }

    suspend fun findFiles(query: String): List<String> =
        runCatching { api.findFiles(query, limit = 40, directory = projectDir()) }.getOrDefault(emptyList())

    // ---- git ----

    fun refreshGit() {
        viewModelScope.launch {
            val dir = projectDir()
            val branch = runCatching { api.vcs(dir).branch }.getOrNull()
            val changes = if (branch != null) git.status(dir) else null
            _ui.update { it.copy(branch = branch, changes = changes) }
        }
    }

    suspend fun diff(change: GitChange): List<DiffLine> {
        val result = git.diff(change, projectDir())
        return DiffLines.parsePatch(result.output)
    }

    suspend fun commit(message: String): GitResult {
        val result = runCatching { git.commitAll(message, projectDir()) }
            .getOrElse { GitResult(it.message ?: "Commit failed", -1) }
        refreshGit()
        return result
    }
}
