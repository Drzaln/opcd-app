package dev.opencode.mobile

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.opencode.mobile.ui.chat.ChatScreen
import dev.opencode.mobile.ui.diff.DiffScreen
import dev.opencode.mobile.ui.file.FileViewerScreen
import dev.opencode.mobile.ui.files.FilesScreen
import dev.opencode.mobile.ui.servers.ServersScreen
import dev.opencode.mobile.ui.sessions.SessionsScreen
import dev.opencode.mobile.ui.theme.OpenCodeTheme

object Routes {
    const val SERVERS = "servers"
    const val SESSIONS = "sessions/{serverId}"
    const val CHAT = "chat/{serverId}/{sessionId}"
    const val FILES = "files/{serverId}?dir={dir}"
    const val FILE = "file/{serverId}?path={path}"
    const val DIFF = "diff/{serverId}/{sessionId}?messageID={messageID}"

    fun sessions(serverId: String) = "sessions/$serverId"
    fun chat(serverId: String, sessionId: String) = "chat/$serverId/$sessionId"
    fun files(serverId: String, dir: String) = "files/$serverId?dir=${java.net.URLEncoder.encode(dir, "UTF-8")}"
    fun file(serverId: String, path: String) = "file/$serverId?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
    fun diff(serverId: String, sessionId: String, messageId: String? = null) =
        "diff/$serverId/$sessionId?messageID=${messageId ?: ""}"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenCodeTheme {
                val app = application as OpenCodeApp
                val appVm: AppViewModel = viewModel(
                    factory = viewModelFactory { initializer { AppViewModel(app) } },
                )
                val active by appVm.activeServer.collectAsState()
                val navController = rememberNavController()

                LaunchedEffect(active?.id) {
                    val destination = if (active == null) Routes.SERVERS else Routes.sessions(active!!.id)
                    navController.navigate(destination) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }

                NavHost(navController = navController, startDestination = Routes.SERVERS) {
                    composable(Routes.SERVERS) {
                        ServersScreen(appVm = appVm, onOpen = { id -> navController.navigate(Routes.sessions(id)) })
                    }
                    composable(
                        route = Routes.SESSIONS,
                        arguments = listOf(androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.StringType }),
                    ) { entry ->
                        val serverId = entry.arguments?.getString("serverId") ?: return@composable
                        SessionsScreen(
                            appVm = appVm,
                            serverId = serverId,
                            onChat = { sessionId -> navController.navigate(Routes.chat(serverId, sessionId)) },
                            onFiles = { navController.navigate(Routes.files(serverId, "")) },
                            onDiff = { sessionId -> navController.navigate(Routes.diff(serverId, sessionId)) },
                            onBack = { appVm.clearActive(); navController.navigate(Routes.SERVERS) { popUpTo(0) { inclusive = true } } },
                        )
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
                        FilesScreen(
                            appVm = appVm,
                            serverId = serverId,
                            dir = dir,
                            onOpenDir = { d -> navController.navigate(Routes.files(serverId, d)) },
                            onOpenFile = { path -> navController.navigate(Routes.file(serverId, path)) },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(
                        route = Routes.FILE,
                        arguments = listOf(
                            androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.StringType },
                            androidx.navigation.navArgument("path") {
                                type = androidx.navigation.NavType.StringType
                                defaultValue = ""
                            },
                        ),
                    ) { entry ->
                        val serverId = entry.arguments?.getString("serverId") ?: return@composable
                        val path = entry.arguments?.getString("path") ?: ""
                        FileViewerScreen(
                            appVm = appVm,
                            serverId = serverId,
                            path = path,
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