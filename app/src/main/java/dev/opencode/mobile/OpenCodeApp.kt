package dev.opencode.mobile

import android.app.Application
import dev.opencode.mobile.data.net.OpenCodeRepository
import dev.opencode.mobile.data.net.ServerStore

class OpenCodeApp : Application() {
    val serverStore: ServerStore by lazy { ServerStore(this) }
    val repository: OpenCodeRepository by lazy { OpenCodeRepository() }
}