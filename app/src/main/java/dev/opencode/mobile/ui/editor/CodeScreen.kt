package dev.opencode.mobile.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.AppViewModel
import dev.opencode.mobile.ui.common.AppBottomBar
import dev.opencode.mobile.ui.common.AppNavRail
import dev.opencode.mobile.ui.common.AppTab
import dev.opencode.mobile.ui.common.ConnectionIndicator
import dev.opencode.mobile.ui.files.FilesScreen
import dev.opencode.mobile.ui.terminal.TerminalScreen
import dev.opencode.mobile.ui.theme.OcTheme

/** Width at which the Code tab becomes file tree | editor (phone landscape, tablets). */
const val CODE_WIDE_DP = 720
/** Width at which the right-hand slot (reserved for the Phase 2 live emulator) appears. */
const val CODE_THREE_PANE_DP = 1000

/**
 * The Code tab. Narrow: the project's file tree (plus recent files); tapping a file opens the editor
 * route. Wide (≥ [CODE_WIDE_DP]): a small IDE — tree 240 dp | editor | optional Terminal/Git bottom
 * panel, with an empty 300 dp right slot on ≥ [CODE_THREE_PANE_DP].
 */
@Composable
fun CodeScreen(
    appVm: AppViewModel,
    serverId: String,
    onOpenFile: (path: String, line: Int?) -> Unit,
    onViewReadOnly: (String) -> Unit,
    onTab: (AppTab) -> Unit,
    /** Opened in the wide layout's editor on entry (file links from chat / diffs on a tablet). */
    openPath: String? = null,
    openLine: Int? = null,
) {
    val servers by appVm.servers.collectAsState()
    val server = remember(servers, serverId) { servers.firstOrNull { it.id == serverId } }
    if (server == null) {
        Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Server not found.", color = OcTheme.colors.textSecondary) }
        return
    }
    val vm = rememberEditorViewModel(appVm, server)
    val recent by vm.recentFiles.collectAsState(initial = emptyList())
    val dir by appVm.currentDirectory.collectAsState()
    val projectName = dir?.trimEnd('/')?.substringAfterLast('/') ?: "Project"
    var treeDir by rememberSaveable(dir) { mutableStateOf("") }
    LaunchedEffect(openPath, openLine) { if (!openPath.isNullOrBlank()) vm.open(openPath, openLine) }

    BoxWithConstraints(Modifier.fillMaxSize().background(OcTheme.colors.bg)) {
        if (maxWidth.value < CODE_WIDE_DP) {
            BackHandler(enabled = treeDir.isNotEmpty()) { treeDir = treeDir.substringBeforeLast('/', "") }
            FilesScreen(
                appVm = appVm,
                serverId = serverId,
                dir = treeDir,
                onOpenDir = { treeDir = it },
                onOpenFile = onOpenFile,
                onBack = null,
                recentFiles = recent,
                onViewReadOnly = onViewReadOnly,
                title = projectName,
                bottomBar = { AppBottomBar(AppTab.CODE, onTab) },
            )
        } else {
            WideCode(
                appVm = appVm,
                vm = vm,
                serverId = serverId,
                serverName = server.name,
                projectName = projectName,
                treeDir = treeDir,
                onTreeDir = { treeDir = it },
                recent = recent,
                onViewReadOnly = onViewReadOnly,
                threePane = maxWidth.value >= CODE_THREE_PANE_DP,
                tall = maxHeight.value >= 600,
                onTab = onTab,
            )
        }
    }
}

private enum class BottomPanel { TERMINAL, GIT }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WideCode(
    appVm: AppViewModel,
    vm: EditorViewModel,
    serverId: String,
    serverName: String,
    projectName: String,
    treeDir: String,
    onTreeDir: (String) -> Unit,
    recent: List<String>,
    onViewReadOnly: (String) -> Unit,
    threePane: Boolean,
    tall: Boolean,
    onTab: (AppTab) -> Unit,
) {
    val c = OcTheme.colors
    val ui by vm.ui.collectAsState()
    // Short (landscape phone) screens: while typing *in the editor*, give it the whole height. Typing in
    // the terminal or a dialog must keep the panel (and anything open in it) composed.
    val imeVisible = WindowInsets.isImeVisible && ui.editorFocused && !tall
    val connection by appVm.connection.collectAsState()
    var panel by rememberSaveable { mutableStateOf(if (tall) BottomPanel.TERMINAL else null) }
    var lastPanel by rememberSaveable { mutableStateOf(BottomPanel.TERMINAL) }
    LaunchedEffect(Unit) { vm.refreshGit() }

    Row(Modifier.fillMaxSize()) {
    if (!tall) {
        AppNavRail(AppTab.CODE, onTab)
        VerticalDivider()
    }
    Column(Modifier.weight(1f).fillMaxHeight().imePadding()) {
        // Header: project · Mac + status dot · branch · N changed · Go to file (Ctrl P) · Save.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(projectName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.textPrimary, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(serverName.ifBlank { "Mac" }, fontSize = 12.sp, color = c.textSecondary, maxLines = 1)
                ConnectionIndicator(state = connection, onRecheck = { appVm.refreshConnection() })
            }
            if (ui.branch != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { panel = BottomPanel.GIT; lastPanel = BottomPanel.GIT }
                        .heightIn(min = 40.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.AccountTree, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(gitLabel(ui), fontSize = 12.sp, color = c.textSecondary, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .width(300.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.surface)
                    .clickable { vm.showPalette(true) }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Go to file", fontSize = 13.sp, color = c.textSecondary, modifier = Modifier.weight(1f))
                Text("Ctrl P", fontSize = 11.sp, color = c.lineNumber)
            }
            ui.activeTab?.let { SaveButton(vm = vm, tab = it) }
            EditorOverflowMenu(vm = vm, onViewReadOnly = onViewReadOnly)
        }
        Divider()

        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.width(240.dp).fillMaxHeight()) {
                FilesScreen(
                    appVm = appVm,
                    serverId = serverId,
                    dir = treeDir,
                    onOpenDir = onTreeDir,
                    onOpenFile = { path, line -> vm.open(path, line) },
                    onBack = null,
                    recentFiles = recent,
                    onViewReadOnly = onViewReadOnly,
                    selectedPath = ui.active,
                    title = "Explorer",
                    compact = true,
                )
            }
            VerticalDivider()
            Column(Modifier.weight(1f).fillMaxHeight()) {
                EditorPane(vm = vm, appVm = appVm, serverId = serverId, onViewReadOnly = onViewReadOnly, modifier = Modifier.weight(1f), showGit = false)
                if (!imeVisible) {
                Divider()
                // Bottom panel tabs: Terminal · Git (Metro / Logcat arrive with the Phase 2 helper).
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (tab in BottomPanel.values()) {
                        val selected = panel == tab
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) c.surface else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { panel = tab; lastPanel = tab }
                                .heightIn(min = 40.dp)
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (tab == BottomPanel.TERMINAL) "Terminal" else "Git",
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) c.textPrimary else c.textSecondary,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { panel = if (panel == null) lastPanel else null }) {
                        Icon(
                            if (panel == null) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = if (panel == null) "Show panel" else "Hide panel",
                            tint = c.textSecondary,
                        )
                    }
                }
                when (panel) {
                    BottomPanel.TERMINAL -> Box(Modifier.fillMaxWidth().height(190.dp)) {
                        TerminalScreen(appVm = appVm, serverId = serverId, onBack = {}, embedded = true)
                    }
                    BottomPanel.GIT -> ChangesPanel(vm = vm, modifier = Modifier.fillMaxWidth().height(190.dp))
                    null -> Unit
                }
                }
            }
            if (threePane) {
                VerticalDivider()
                // Reserved for the Phase 2 Mac helper's live emulator stream.
                Box(Modifier.width(300.dp).fillMaxHeight().background(c.surface), contentAlignment = Alignment.Center) {
                    Text(
                        "Emulator preview arrives with the Mac helper",
                        fontSize = 12.sp,
                        color = c.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
        if (!imeVisible && tall) {
            Divider()
            AppBottomBar(AppTab.CODE, onTab)
        }
    }
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(OcTheme.colors.border))

@Composable
private fun VerticalDivider() = Box(Modifier.width(1.dp).fillMaxHeight().background(OcTheme.colors.border))
