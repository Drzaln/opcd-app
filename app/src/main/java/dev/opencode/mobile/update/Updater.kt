package dev.opencode.mobile.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val version: String) : UpdateState
    data class Downloading(val version: String, val progress: Int) : UpdateState
    data class Ready(val version: String, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks GitHub Releases without the REST API (no rate limit): the
 * /releases/latest web URL 302-redirects to /releases/tag/v<version>, and the
 * fixed /releases/latest/download/<asset> URL always resolves to the newest asset.
 */
class Updater(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val currentVersion: String
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()

    suspend fun latestVersion(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$BASE_URL/releases/latest").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val finalUrl = response.request.url.toString()
                Regex("/releases/tag/v?(.+)$").find(finalUrl)?.groupValues?.get(1)?.trim()
            }
        }.getOrNull()
    }

    fun isNewer(remote: String, current: String): Boolean {
        fun parts(value: String) = value.trim().removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val a = parts(remote)
        val b = parts(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun download(onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$BASE_URL/releases/latest/download/$APK_NAME").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
            val body = response.body ?: error("Download failed: empty body")
            val total = body.contentLength()
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val target = File(dir, APK_NAME)
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPercent = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        read += count
                        if (total > 0) {
                            val percent = ((read * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            target
        }
    }

    fun canRequestInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        private const val BASE_URL = "https://github.com/Drzaln/opcd-app"
        private const val APK_NAME = "app-release.apk"
    }
}
