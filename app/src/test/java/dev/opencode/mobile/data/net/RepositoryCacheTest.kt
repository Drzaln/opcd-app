package dev.opencode.mobile.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** bd g98: "Test connection" must probe what's in the dialog, not a cached client. */
class RepositoryCacheTest {
    private val repo = OpenCodeRepository()

    @Test
    fun probeUsesTheTypedServerEveryTime() {
        val first = repo.probeApi(ServerConfig(id = "", baseUrl = "http://100.94.110.59:4096", password = "a"))
        val second = repo.probeApi(ServerConfig(id = "", baseUrl = "http://127.0.0.1:4199", password = "secret"))
        assertNotSame(first, second)
        assertEquals("http://127.0.0.1:4199", second.server.baseUrl)
        assertEquals("secret", second.server.password)
    }

    @Test
    fun editingAServerDropsItsCachedClient() {
        val old = repo.apiFor(ServerConfig(id = "mac", baseUrl = "http://100.94.110.59:4096"))
        assertSame(old, repo.apiFor(ServerConfig(id = "mac", baseUrl = "http://100.94.110.59:4096")))
        repo.forget("mac")
        val edited = repo.apiFor(ServerConfig(id = "mac", baseUrl = "http://100.94.110.59:4100"))
        assertNotSame(old, edited)
        assertEquals("http://100.94.110.59:4100", edited.server.baseUrl)
    }
}
