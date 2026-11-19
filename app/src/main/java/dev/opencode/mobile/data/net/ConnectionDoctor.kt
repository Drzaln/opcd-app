package dev.opencode.mobile.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

enum class DiagnosisKind { PHONE_OFFLINE, TAILSCALE_OFF, MAC_UNREACHABLE, OPENCODE_STOPPED, WRONG_PASSWORD, NAME_NOT_FOUND, NOT_OPENCODE, UNKNOWN }

/** Why the server can't be reached, in words the user can act on. */
data class Diagnosis(
    val kind: DiagnosisKind,
    val title: String,
    val advice: String,
    /** The raw error, for the details view. */
    val detail: String? = null,
)

/**
 * Works out *why* a server doesn't answer, cheapest check first: does the phone have a network, is the
 * Tailscale VPN up (for a Tailscale address), and then a bare TCP connect to the server's port — a
 * refused connection means the Mac is up but nothing listens (opencode stopped); a timeout means the
 * Mac itself is unreachable (asleep, off, or off Tailscale).
 */
object ConnectionDoctor {

    /** Tailscale addresses: 100.64.0.0/10, MagicDNS names, and the Tailscale IPv6 range. */
    fun isTailscaleHost(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        if (h.endsWith(".ts.net")) return true
        if (h.startsWith("fd7a:115c:a1e0:")) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }

    enum class Tcp { OPEN, REFUSED, TIMEOUT, UNREACHABLE, UNRESOLVED, OTHER }

    /** Classifies a probe failure from the error text alone (no network): used when there's no context. */
    fun classifyHttpError(error: String?): DiagnosisKind? = when {
        error == null -> null
        Regex("\\b401\\b").containsMatchIn(error) -> DiagnosisKind.WRONG_PASSWORD
        else -> null
    }

    suspend fun diagnose(context: Context, server: ServerConfig, error: String?): Diagnosis = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(server.baseUrl.trim()) }.getOrNull()
        val host = uri?.host ?: server.baseUrl.substringAfter("://").substringBefore('/').substringBefore(':')
        val port = uri?.port?.takeIf { it > 0 } ?: if (uri?.scheme == "https") 443 else 80
        val name = server.name.ifBlank { "the Mac" }

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val localHost = host == "127.0.0.1" || host == "localhost"
        if (active == null && !localHost) {
            return@withContext Diagnosis(
                DiagnosisKind.PHONE_OFFLINE,
                "This phone is offline",
                "No Wi-Fi or mobile data. Saved sessions and files are still readable; prompts you send wait until you're back online.",
                error,
            )
        }
        @Suppress("DEPRECATION")
        val vpnUp = cm?.allNetworks?.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } == true
        if (isTailscaleHost(host) && !vpnUp) {
            return@withContext Diagnosis(
                DiagnosisKind.TAILSCALE_OFF,
                "Tailscale is off on this phone",
                "$host is a Tailscale address. Turn Tailscale on, then retry.",
                error,
            )
        }
        if (classifyHttpError(error) == DiagnosisKind.WRONG_PASSWORD) {
            return@withContext Diagnosis(
                DiagnosisKind.WRONG_PASSWORD,
                "Wrong username or password",
                "opencode on $name answered but refused the login. Edit the server and check its password.",
                error,
            )
        }
        when (tcp(host, port)) {
            Tcp.REFUSED -> Diagnosis(
                DiagnosisKind.OPENCODE_STOPPED,
                "opencode isn't running",
                "${name.replaceFirstChar { it.uppercase() }} is on, but nothing answers on port $port. Start opencode on the Mac (opcd-expose).",
                error,
            )
            Tcp.TIMEOUT, Tcp.UNREACHABLE -> Diagnosis(
                DiagnosisKind.MAC_UNREACHABLE,
                "Can't reach $name",
                if (isTailscaleHost(host)) "It's asleep, turned off, or signed out of Tailscale. A MacBook sleeps when the lid closes or on battery; plug it in and open it, then retry."
                else "It's asleep, turned off, or on another network.",
                error,
            )
            Tcp.UNRESOLVED -> Diagnosis(
                DiagnosisKind.NAME_NOT_FOUND,
                "Can't find $host",
                if (host.endsWith(".ts.net")) "The Tailscale name didn't resolve. Check MagicDNS is on, or use the Mac's 100.x address."
                else "The address didn't resolve. Check the server address.",
                error,
            )
            Tcp.OPEN -> Diagnosis(
                DiagnosisKind.NOT_OPENCODE,
                "$name answers, but not like opencode",
                "Something is listening on port $port but the health check failed. Check the address and that opencode is the one on that port.",
                error,
            )
            Tcp.OTHER -> Diagnosis(DiagnosisKind.UNKNOWN, "Can't connect to $name", error ?: "Unknown error", error)
        }
    }

    private fun tcp(host: String, port: Int): Tcp = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 4000) }
        Tcp.OPEN
    } catch (_: UnknownHostException) {
        Tcp.UNRESOLVED
    } catch (_: SocketTimeoutException) {
        Tcp.TIMEOUT
    } catch (_: NoRouteToHostException) {
        Tcp.UNREACHABLE
    } catch (e: ConnectException) {
        val m = e.message.orEmpty()
        when {
            "ECONNREFUSED" in m || "refused" in m.lowercase() -> Tcp.REFUSED
            "EHOSTUNREACH" in m || "ENETUNREACH" in m -> Tcp.UNREACHABLE
            "ETIMEDOUT" in m -> Tcp.TIMEOUT
            else -> Tcp.OTHER
        }
    } catch (_: Exception) {
        Tcp.OTHER
    }
}
