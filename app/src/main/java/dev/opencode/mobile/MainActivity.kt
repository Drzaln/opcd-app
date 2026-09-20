package dev.opencode.mobile

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.opencode.mobile.ui.chat.ChatScreen
import dev.opencode.mobile.ui.common.PermissionSheet
import dev.opencode.mobile.ui.common.QuestionSheet
import dev.opencode.mobile.ui.diff.DiffScreen
import dev.opencode.mobile.ui.file.FileViewerScreen
import dev.opencode.mobile.ui.files.FilesScreen
import dev.opencode.mobile.ui.servers.ServersScreen
import dev.opencode.mobile.ui.sessions.SessionsScreen
import dev.opencode.mobile.ui.theme.OpenCodeTheme
import dev.opencode.mobile.ui.theme.Motion
import dev.opencode.mobile.update.UpdateState

object Routes {
    const val SERVERS = "servers"
    const val SETTINGS = "settings"
    const val TERMINAL = "terminal/{serverId}"
    const val SESSIONS = "sessions/{serverId}"
    const val CHAT = "chat/{serverId}/{sessionId}"
    const val FILES = "files/{serverId}?dir={dir}"
    const val FILE = "file/{serverId}?path={path}&line={line}"
    const val DIFF = "diff/{serverId}/{sessionId}?messageID={messageID}"

    fun sessions(serverId: String) = "sessions/$serverId"
    fun terminal(serverId: String) = "terminal/$serverId"
    fun chat(serverId: String, sessionId: String) = "chat/$serverId/$sessionId"
    fun files(serverId: String, dir: String) = "files/$serverId?dir=${java.net.URLEncoder.encode(dir, "UTF-8")}"
    fun file(serverId: String, path: String, line: Int? = null) = "file/$serverId?path=${java.net.URLEncoder.encode(path, "UTF-8")}&line=${line ?: -1}"
    fun diff(serverId: String, sessionId: String, messageId: String? = null) =
        "diff/$serverId/$sessionId?messageID=${messageId ?: ""}"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app = application as OpenCodeApp
            val appVm: AppViewModel = viewModel(
                factory = viewModelFactory { initializer { AppViewModel(app) } },
            )
            val themeMode by appVm.themeMode.collectAsState()
            OpenCodeTheme(mode = themeMode) {
                val active by appVm.activeServer.collectAsState()
                val navController = rememberNavController()

                val update by appVm.update.collectAsState()
                var updateDismissed by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { appVm.checkForUpdate() }
                if (!updateDismissed) {
                    UpdateDialog(
                        state = update,
                        onDownload = { version -> appVm.downloadUpdate(version) },
                        onInstall = { file -> appVm.installUpdate(file) },
                        onDismiss = {
                            updateDismissed = true
                            appVm.dismissUpdate((update as? UpdateState.Available)?.version)
                        },
                    )
                }

                // App-wide blocking prompts (permission + question sheets) so they are never missed.
                val prompts by appVm.prompts.collectAsState()
                LaunchedEffect(active?.id, appVm) { appVm.refreshPrompts() }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { appVm.refreshPrompts() }
                prompts.permissions.firstOrNull()?.let { permission ->
                    PermissionSheet(
                        request = permission,
                        pending = prompts.permissions.size,
                        onRespond = { response -> appVm.respondPermission(permission, response) },
                    )
                }
                prompts.questions.firstOrNull()?.let { question ->
                    QuestionSheet(
                        request = question,
                        pending = prompts.questions.size,
                        onSubmit = { answers -> appVm.answerQuestion(question, answers) },
                        onDismiss = { appVm.rejectQuestion(question) },
                    )
                }

                LaunchedEffect(active?.id) {
                    val destination = if (active == null) Routes.SERVERS else Routes.sessions(active!!.id)
                    navController.navigate(destination) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }

                val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 720
                var selectedSession by remember { mutableStateOf<String?>(null) }
                var selectedFile by remember { mutableStateOf<String?>(null) }
                var selectedLine by remember { mutableStateOf<Int?>(null) }
                LaunchedEffect(active?.id) {
                    selectedSession = null
                    selectedFile = null
                }

                NavHost(
                    navController = navController,
                    startDestination = Routes.SERVERS,
                    enterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(Motion.MEDIUM, easing = FastOutSlowInEasing),
                        )
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(Motion.MEDIUM, easing = FastOutSlowInEasing),
                        )
                    },
                    popEnterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(Motion.MEDIUM, easing = FastOutSlowInEasing),
                        )
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(Motion.MEDIUM, easing = FastOutSlowInEasing),
                        )
                    },
                ) {
                    composable(Routes.SERVERS) {
                        ServersScreen(
                            appVm = appVm,
                            onOpen = { id -> navController.navigate(Routes.sessions(id)) },
                            onSettings = { navController.navigate(Routes.SETTINGS) },
                        )
                    }
                    composable(Routes.SETTINGS) {
                        dev.opencode.mobile.ui.settings.SettingsScreen(
                            appVm = appVm,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        route = Routes.TERMINAL,
                        arguments = listOf(androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.StringType }),
                    ) { entry ->
                        val serverId = entry.arguments?.getString("serverId") ?: return@composable
                        dev.opencode.mobile.ui.terminal.TerminalScreen(
                            appVm = appVm,
                            serverId = serverId,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        route = Routes.SESSIONS,
                        arguments = listOf(androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.StringType }),
                    ) { entry ->
                        val serverId = entry.arguments?.getString("serverId") ?: return@composable
                        val sessionsPane: @Composable () -> Unit = {
                            SessionsScreen(
                                appVm = appVm,
                                serverId = serverId,
                                onChat = { sessionId ->
                                    if (wide) selectedSession = sessionId
                                    else navController.navigate(Routes.chat(serverId, sessionId))
                                },
                                onFiles = { navController.navigate(Routes.files(serverId, "")) },
                                onSettings = { navController.navigate(Routes.SETTINGS) },
                                onTerminal = { navController.navigate(Routes.terminal(serverId)) },
                                onDiff = { sessionId -> navController.navigate(Routes.diff(serverId, sessionId)) },
                                onBack = { appVm.clearActive(); navController.navigate(Routes.SERVERS) { popUpTo(0) { inclusive = true } } },
                            )
                        }
                        if (wide) {
                            WideTwoPane(
                                list = sessionsPane,
                                detail = {
                                    val sessionId = selectedSession
                                    if (sessionId == null) {
                                        EmptyPane("Pick a session to open it here")
                                    } else {
                                        ChatScreen(
                                            appVm = appVm,
                                            serverId = serverId,
                                            sessionId = sessionId,
                                            onBack = { selectedSession = null },
                                            onDiff = { navController.navigate(Routes.diff(serverId, sessionId)) },
                                            onMessageDiff = { messageId -> navController.navigate(Routes.diff(serverId, sessionId, messageId)) },
                                            onOpenFile = { path -> navController.navigate(Routes.file(serverId, path)) },
                                            onOpenSession = { selectedSession = it },
                                        )
                                    }
                                },
                            )
                        } else {
                            sessionsPane()
                        }
                    }
                    composable(
                        route = Routes.CHAT,
                        arguments = listOf(
                            androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.StringType },
                            androidx.navigation.navArgument("sessionId") { type = androidx.navigation.NavType.StringType },
                        ),
                    ) { entry ->
                        val serverId = entry.arguments?.getString("serverId") ?: return@composable
                        val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
                        ChatScreen(
                            appVm = appVm,
                            serverId = serverId,
                            sessionId = sessionId,
                            onBack = { navController.popBackStack() },
                            onDiff = { navController.navigate(Routes.diff(serverId, sessionId)) },
                            onMessageDiff = { messageId -> navController.navigate(Routes.diff(serverId, sessionId, messageId)) },
                            onOpenFile = { path ->
                                navController.navigate(Routes.file(serverId, path))
                            },
                            onOpenSession = { newSessionId ->
                                navController.navigate(Routes.chat(serverId, newSessionId)) {
                                    popUpTo(Routes.chat(serverId, sessionId)) { inclusive = true }
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                    composable(
                        route = Routes.FILES,
                        arguments = listOf(
                            androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.StringType },
                            androidx.navigation.navArgument("dir") {
                                type = androidx.navigation.NavType.StringType
                                defaultValue = ""
                            },
                        ),
                    ) { entry ->
                        val serverId = entry.arguments?.getString("serverId") ?: return@composable
                        val dir = entry.arguments?.getString("dir") ?: ""
                        val filesPane: @Composable () -> Unit = {
                            FilesScreen(
                                appVm = appVm,
                                serverId = serverId,
                                dir = dir,
                                onOpenDir = { d -> navController.navigate(Routes.files(serverId, d)) },
                                onOpenFile = { path, line ->
                                    if (wide) {
                                        selectedFile = path
                                        selectedLine = line
                                    } else {
                                        navController.navigate(Routes.file(serverId, path, line))
                                    }
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }
                        if (wide) {
                            WideTwoPane(
                                list = filesPane,
                                detail = {
                                    val path = selectedFile
                                    if (path == null) EmptyPane("Pick a file to preview it here")
                                    else FileViewerScreen(
                                        appVm = appVm,
                                        serverId = serverId,
                                        path = path,
                                        line = selectedLine,
                                        onBack = { selectedFile = null },
                                    )
                                },
                            )
                        } else {
                            filesPane()
                        }
                    }
                    composable(
                        route = Routes.FILE,
                        arguments = listOf(
                            androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.StringType },
                            androidx.navigation.navArgument("path") {
                                type = androidx.navigation.NavType.StringType
                                defaultValue = ""
                            },
                            androidx.navigation.navArgument("line") {
                                type = androidx.navigation.NavType.IntType
                                defaultValue = -1
                            },
                        ),
                    ) { entry ->
                        val serverId = entry.arguments?.getString("serverId") ?: return@composable
                        val path = entry.arguments?.getString("path") ?: ""
                        val line = entry.arguments?.getInt("line")?.takeIf { it > 0 }
                        FileViewerScreen(
                            appVm = appVm,
                            serverId = serverId,
                            path = path,
                            line = line,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        route = Routes.DIFF,
                        arguments = listOf(
                            androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.StringType },
                            androidx.navigation.navArgument("sessionId") { type = androidx.navigation.NavType.StringType },
                            androidx.navigation.navArgument("messageID") {
                                type = androidx.navigation.NavType.StringType
                                defaultValue = ""
                            },
                        ),
                    ) { entry ->
                        val serverId = entry.arguments?.getString("serverId") ?: return@composable
                        val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
                        val messageID = entry.arguments?.getString("messageID")?.ifBlank { null }
                        DiffScreen(
                            appVm = appVm,
                            serverId = serverId,
                            sessionId = sessionId,
                            messageId = messageID,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun WideTwoPane(
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.42f).fillMaxHeight()) { list() }
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline),
        )
        Box(Modifier.weight(0.58f).fillMaxHeight()) { detail() }
    }
}

@androidx.compose.runtime.Composable
private fun EmptyPane(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@androidx.compose.runtime.Composable
private fun UpdateDialog(
    state: UpdateState,
    onDownload: (String) -> Unit,
    onInstall: (java.io.File) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Update available") },
            text = { Text("opencode mobile v${state.version} is available. Download and install it now?") },
            confirmButton = { TextButton(onClick = { onDownload(state.version) }) { Text("Download & install") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update") },
            text = {
                Column {
                    Text("v${state.version} — ${state.progress}%")
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {},
        )

        is UpdateState.Ready -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Ready to install") },
            text = { Text("Android will ask you to confirm installing v${state.version}. If installs are blocked, allow them for this app and tap Install again.") },
            confirmButton = { TextButton(onClick = { onInstall(state.file) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Update failed") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )

        UpdateState.Idle -> Unit
    }
}