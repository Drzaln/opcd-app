package dev.opencode.mobile.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dev.opencode.mobile.OpenCodeApp
import dev.opencode.mobile.data.net.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class SessionWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var notifyId = 100

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        ServiceCompat.startForeground(
            this,
            Notifications.WATCH_ID,
            Notifications.watchNotification(this, "Watching for finished turns…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startWatch()
        return START_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startWatch() {
        job?.cancel()
        val app = applicationContext as OpenCodeApp
        job = scope.launch {
            app.serverStore.activeId
                .flatMapLatest { id ->
                    app.serverStore.servers.map { list -> list.firstOrNull { it.id == id } }
                }
                .filterNotNull()
                .distinctUntilChanged()
                .collectLatest { server -> watchServer(app, server) }
        }
    }

    private suspend fun watchServer(app: OpenCodeApp, server: ServerConfig) {
        val api = app.repository.apiFor(server)
        app.repository.events(server).collect { event ->
            when (event.type) {
                "session.idle" -> {
                    val sessionId = (event.data as? JsonObject)?.get("sessionID")?.jsonPrimitive?.contentOrNull
                    val title = sessionId?.let { runCatching { api.session(it) }.getOrNull()?.title }.orEmpty().ifEmpty { "Session" }
                    Notifications.post(this@SessionWatchService, notifyId++, "opencode finished", title)
                }
                "permission.updated" -> {
                    val data = event.data as? JsonObject
                    val title = data?.get("title")?.jsonPrimitive?.contentOrNull ?: "Permission requested"
                    Notifications.post(this@SessionWatchService, notifyId++, "opencode needs you", title)
                }
            }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.opencode.mobile.STOP_WATCH"

        fun start(context: Context) {
            val intent = Intent(context, SessionWatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionWatchService::class.java))
        }
    }
}