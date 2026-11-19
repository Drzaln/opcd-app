package dev.opencode.mobile

import android.app.Application
import dev.opencode.mobile.data.cache.CacheStore
import dev.opencode.mobile.data.net.OpenCodeRepository
import dev.opencode.mobile.data.net.ServerStore
import dev.opencode.mobile.notify.Notifications

class OpenCodeApp : Application() {
    val serverStore: ServerStore by lazy { ServerStore(this) }
    val repository: OpenCodeRepository by lazy { OpenCodeRepository() }
    val cacheStore: CacheStore by lazy { CacheStore(this) }
    /** Prompts typed while the server was unreachable; sent when it answers again. */
    val outbox: dev.opencode.mobile.data.cache.Outbox by lazy { dev.opencode.mobile.data.cache.Outbox(cacheStore, repository) }
    /** Optional Mac helper (TypeScript language server, format on save, …) for the active server. */
    val helper: dev.opencode.mobile.data.net.HelperManager by lazy { dev.opencode.mobile.data.net.HelperManager(serverStore) }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }
}