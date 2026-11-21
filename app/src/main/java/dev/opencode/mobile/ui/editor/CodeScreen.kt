package dev.opencode.mobile.ui.editor

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.material.icons.outlined.CallToAction
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.opencode.mobile.ui.common.DragGrip
import dev.opencode.mobile.ui.common.HorizontalSplit
import dev.opencode.mobile.ui.common.rememberPaneState
import dev.opencode.mobile.ui.common.verticalResize
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
                actions = { MetroAction(appVm = appVm, vm = vm); AndroidAction(appVm = appVm, vm = vm) },
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
                roomy = maxHeight.value >= 720,
                onTab = onTab,
            )
        }
    }
}

private enum class BottomPanel(val label: String) { TERMINAL("Terminal"), GIT("Git"), METRO("Metro"), ANDROID("Android") }

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
    /** Height to spare (≥ 720 dp, e.g. a 10" tablet in landscape): the bottom panel starts open and the
     *  editor keeps it while typing. On a 7" tablet in landscape (600 dp) the editor needs that room. */
    roomy: Boolean = tall,
    onTab: (AppTab) -> Unit,
) {
    val c = OcTheme.colors
    val ui by vm.ui.collectAsState()
    // Short (landscape phone) screens: while typing *in the editor*, give it the whole height. Typing in
    // the terminal or a dialog must keep the panel (and anything open in it) composed.
    val imeVisible = WindowInsets.isImeVisible && ui.editorFocused && !roomy
    val connection by appVm.connection.collectAsState()
    // Resizable / hideable panes, remembered per orientation (see SplitPane.kt).
    val treePane = rememberPaneState("code.tree", default = 240.dp, min = 180.dp, max = 440.dp)
    val sidePane = rememberPaneState("code.side", default = 300.dp, min = 240.dp, max = 560.dp)
    val panelPane = rememberPaneState("code.panel", default = if (roomy) 240.dp else 190.dp, min = 120.dp, max = 900.dp, defaultVisible = roomy)
    var panel by rememberSaveable { mutableStateOf(if (panelPane.visible) BottomPanel.TERMINAL else null) }
    var lastPanel by rememberSaveable { mutableStateOf(BottomPanel.TERMINAL) }
    // Keep the saved visibility in step with the panel however it was opened or closed.
    LaunchedEffect(panel) { panelPane.show(panel != null) }
    fun togglePanel() { panel = if (panel == null) lastPanel else null }
    // Ctrl+B / Ctrl+J / Ctrl+Alt+B, from the editor (WebView keymap) or anywhere else in the layout.
    LaunchedEffect(vm) {
        vm.layoutCommands.collect { cmd ->
            when (cmd) {
                "toggleTree" -> treePane.toggle()
                "togglePanel" -> togglePanel()
                "toggleSide" -> if (threePane) sidePane.toggle()
            }
        }
    }
    LaunchedEffect(Unit) { vm.refreshGit() }
    // Offline: a file with a saved copy opens in the read-only viewer instead of failing.
    LaunchedEffect(ui.offlineCopy) {
        ui.offlineCopy?.let { vm.offlineCopyShown(); onViewReadOnly(it) }
    }
    val helperState by appVm.helper.collectAsState()
    val metroAvailable = MetroController.available(helperState)
    val metroUi by vm.metro.ui.collectAsState()
    val androidAvailable = AndroidController.available(helperState)
    val androidUi by vm.android.ui.collectAsState()
    val screenAvailable = ScreenController.available(helperState)
    // The helper went away (or never advertised Metro): don't leave an empty Metro panel open.
    LaunchedEffect(metroAvailable) {
        if (!metroAvailable && panel == BottomPanel.METRO) panel = BottomPanel.TERMINAL
        if (!metroAvailable && lastPanel == BottomPanel.METRO) lastPanel = BottomPanel.TERMINAL
    }
    LaunchedEffect(androidAvailable) {
        if (!androidAvailable && panel == BottomPanel.ANDROID) panel = BottomPanel.TERMINAL
        if (!androidAvailable && lastPanel == BottomPanel.ANDROID) lastPanel = BottomPanel.TERMINAL
    }

    Row(Modifier.fillMaxSize()) {
    if (!tall && !dev.opencode.mobile.ui.common.LocalNavRail.current) {
        AppNavRail(AppTab.CODE, onTab)
        VerticalDivider()
    }
    // With the activity's side rail there is no bottom bar to keep the panel off the gesture bar.
    val railed = dev.opencode.mobile.ui.common.LocalNavRail.current
    Column(
        Modifier.weight(1f).fillMaxHeight().then(if (railed) Modifier.navigationBarsPadding() else Modifier).imePadding()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.B -> { if (e.isAltPressed) { if (threePane) sidePane.toggle() } else treePane.toggle(); true }
                    Key.J -> { togglePanel(); true }
                    else -> false
                }
            },
    ) {
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
            LayoutToggles(
                tree = treePane.visible,
                panel = panel != null,
                side = if (threePane) sidePane.visible else null,
                onTree = { treePane.toggle() },
                onPanel = { togglePanel() },
                onSide = { sidePane.toggle() },
            )
            EditorOverflowMenu(vm = vm, onViewReadOnly = onViewReadOnly)
        }
        Divider()

        // tree │ editor (+ bottom panel) │ device column, each divider draggable (SplitPane.kt).
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val bodyHeight = maxHeight
            HorizontalSplit(
                state = treePane,
                modifier = Modifier.fillMaxSize(),
                restMin = if (threePane) 600.dp else 360.dp,
                paneLabel = "file tree",
                sized = {
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
                },
            ) {
                val editorAndPanel: @Composable () -> Unit = {
                    Column(Modifier.fillMaxSize()) {
                        EditorPane(vm = vm, appVm = appVm, serverId = serverId, onViewReadOnly = onViewReadOnly, modifier = Modifier.weight(1f), showGit = false)
                        if (!imeVisible) {
                            Divider()
                            // Bottom panel tabs: Terminal · Git · Metro · Android. Dragging the strip up or
                            // down resizes the panel; dragging it to the bottom hides it, up again shows it.
                            val stripDrag = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            val stripDragging by stripDrag.collectIsDraggedAsState()
                            val panelLimit = (bodyHeight - 160.dp - 48.dp).coerceAtLeast(panelPane.min)
                            Box(
                                Modifier.fillMaxWidth().verticalResize(
                                    state = panelPane,
                                    limit = panelLimit,
                                    interaction = stripDrag,
                                    onShow = { panel = lastPanel },
                                    onHide = { panel = null },
                                ),
                            ) {
                                DragGrip(Modifier.align(Alignment.TopCenter).padding(top = 3.dp), active = stripDragging)
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    for (tab in BottomPanel.values().filter { (it != BottomPanel.METRO || metroAvailable) && (it != BottomPanel.ANDROID || androidAvailable) }) {
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
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    tab.label,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = if (selected) c.textPrimary else c.textSecondary,
                                                )
                                                if (tab == BottomPanel.METRO) MetroTabBadge(metroUi)
                                                if (tab == BottomPanel.ANDROID) AndroidTabBadge(androidUi)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    IconButton(onClick = { togglePanel() }) {
                                        Icon(
                                            if (panel == null) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                            contentDescription = if (panel == null) "Show panel" else "Hide panel",
                                            tint = c.textSecondary,
                                        )
                                    }
                                }
                            }
                            val panelHeight = panelPane.size.coerceAtMost(panelLimit)
                            when (panel) {
                                BottomPanel.TERMINAL -> Box(Modifier.fillMaxWidth().height(panelHeight)) {
                                    TerminalScreen(appVm = appVm, serverId = serverId, onBack = {}, embedded = true)
                                }
                                BottomPanel.GIT -> ChangesPanel(vm = vm, modifier = Modifier.fillMaxWidth().height(panelHeight))
                                BottomPanel.METRO -> MetroPanel(metro = vm.metro, modifier = Modifier.fillMaxWidth().height(panelHeight))
                                BottomPanel.ANDROID -> AndroidPanel(android = vm.android, screen = vm.screen.takeIf { screenAvailable && !threePane }, modifier = Modifier.fillMaxWidth().height(panelHeight))
                                null -> Unit
                            }
                        }
                    }
                }
                if (threePane) {
                    HorizontalSplit(
                        state = sidePane,
                        modifier = Modifier.fillMaxSize(),
                        sizedAtEnd = true,
                        restMin = 360.dp,
                        paneLabel = "device screen",
                        sized = {
                            // The live device screen (scrcpy through the Mac helper), for the device picked
                            // in the Android panel.
                            if (screenAvailable) {
                                val device = androidUi.device?.takeIf { it.ready }
                                DeviceScreen(
                                    screen = vm.screen,
                                    serial = device?.serial,
                                    deviceLabel = device?.label,
                                    isThisPhone = device?.let { isThisPhone(it) } == true,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else Box(Modifier.fillMaxSize().background(c.surface), contentAlignment = Alignment.Center) {
                                Text(
                                    "Device screen: run the Mac helper with scrcpy (Android) or idb-companion (iOS simulators)",
                                    fontSize = 12.sp,
                                    color = c.textSecondary,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(24.dp),
                                )
                            }
                        },
                    ) { editorAndPanel() }
                } else {
                    editorAndPanel()
                }
            }
        }
        if (!imeVisible && tall && !dev.opencode.mobile.ui.common.LocalNavRail.current) {
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

/** Header buttons that show/hide the file tree, bottom panel and device column (Ctrl+B, Ctrl+J, Ctrl+Alt+B). */
@Composable
private fun LayoutToggles(
    tree: Boolean,
    panel: Boolean,
    side: Boolean?,
    onTree: () -> Unit,
    onPanel: () -> Unit,
    onSide: () -> Unit,
) {
    val c = OcTheme.colors
    @Composable
    fun toggle(on: Boolean, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, mirror: Boolean = false, onClick: () -> Unit) {
        IconButton(onClick = onClick) {
            Icon(
                icon,
                contentDescription = if (on) "Hide $label" else "Show $label",
                tint = if (on) c.link else c.textSecondary,
                modifier = Modifier.size(20.dp).then(if (mirror) Modifier.graphicsLayer { scaleX = -1f } else Modifier),
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        toggle(tree, "file tree", Icons.Outlined.ViewSidebar, mirror = true, onClick = onTree)
        toggle(panel, "bottom panel", Icons.Outlined.CallToAction, onClick = onPanel)
        if (side != null) toggle(side, "device screen", Icons.Outlined.ViewSidebar, onClick = onSide)
    }
}
