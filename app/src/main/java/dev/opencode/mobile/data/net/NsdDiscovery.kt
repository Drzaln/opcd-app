package dev.opencode.mobile.data.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
    val baseUrl: String,
)

class NsdDiscovery(private val context: Context) {

    fun discover(timeoutMs: Long = 8000): Flow<DiscoveredServer> = callbackFlow {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val main = Handler(Looper.getMainLooper())
        val results = LinkedHashMap<String, DiscoveredServer>()

        fun isOpenCodeName(name: String): Boolean =
            name.startsWith("opencode-") || name.equals("opencode", ignoreCase = true)

        fun push(info: NsdServiceInfo, host: String) {
            if (!isOpenCodeName(info.serviceName)) return
            val key = "$host:${info.port}"
            if (results.containsKey(key)) return
            val server = DiscoveredServer(
                name = info.serviceName,
                host = host,
                port = info.port,
                baseUrl = "http://$host:${info.port}",
            )
            results[key] = server
            trySend(server)
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                if (host == "0.0.0.0") return
                push(serviceInfo, host)
            }
        }

        val discoverListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != "_http._tcp.") return
                val host = serviceInfo.host?.hostAddress
                if (host != null && host != "0.0.0.0") {
                    push(serviceInfo, host)
                } else if (isOpenCodeName(serviceInfo.serviceName)) {
                    runCatching { manager.resolveService(serviceInfo, resolveListener) }
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        fun stop() {
            runCatching { manager.stopServiceDiscovery(discoverListener) }
        }

        withContext(Dispatchers.Main) {
            manager.discoverServices("_http._tcp", NsdManager.PROTOCOL_DNS_SD, discoverListener)
        }
        main.postDelayed({ stop() }, timeoutMs)

        awaitClose {
            main.post { stop() }
        }
    }
}