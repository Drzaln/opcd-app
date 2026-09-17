package dev.opencode.mobile.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.opencode.mobile.OpenCodeApp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Android 15+ caps dataSync foreground services (~6h/day), after which SessionWatchService stops.
 * This periodic worker is the fallback: it polls status/permissions and notifies on busy→idle or a
 * new blocking prompt, so notifications keep working without a foreground service.
 */
class NotifyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? OpenCodeApp ?: return Result.success()
        val activeId = app.serverStore.activeId.first() ?: return Result.success()
        val server = app.serverStore.servers.first().firstOrNull { it.id == activeId } ?: return Result.success()
        val dir = app.serverStore.currentDirectory.first()
        val api = app.repository.apiFor(server)

        val statuses = runCatching { api.sessionStatus(dir) }.getOrNull() ?: return Result.retry()
        val busyNow = statuses.filterValues { it.type == "busy" || it.type == "retry" }.keys
        val previous = app.serverStore.busySessions.first()
        val finished = previous - busyNow

        Notifications.ensureChannels(applicationContext)
        var id = 200
        for (sessionId in finished) {
            val title = runCatching { api.session(sessionId, dir) }.getOrNull()?.title.orEmpty().ifEmpty { "Session" }
            Notifications.post(applicationContext, id++, "opencode finished", title)
        }
        val permissions = runCatching { api.pendingPermissions(dir) }.getOrNull().orEmpty()
        for (permission in permissions) {
            Notifications.post(
                applicationContext,
                id++,
                "opencode needs permission",
                permission.label,
            )
        }
        val questions = runCatching { api.pendingQuestions(dir) }.getOrNull().orEmpty()
        for (question in questions) {
            val first = question.questions.firstOrNull()
            Notifications.post(
                applicationContext,
                id++,
                "opencode has a question",
                first?.header?.ifBlank { first.question } ?: "Question requested",
            )
        }
        app.serverStore.setBusySessions(busyNow)
        return Result.success()
    }
}

object NotifyScheduler {
    private const val NAME = "opencode-notify-fallback"

    fun enable(context: android.content.Context) {
        val request = PeriodicWorkRequestBuilder<NotifyWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun disable(context: android.content.Context) {
        WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
