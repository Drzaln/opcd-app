package dev.opencode.mobile.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionDoctorTest {
    @Test
    fun recognisesTailscaleAddresses() {
        assertTrue(ConnectionDoctor.isTailscaleHost("100.101.102.103"))
        assertTrue(ConnectionDoctor.isTailscaleHost("100.64.0.1"))
        assertTrue(ConnectionDoctor.isTailscaleHost("100.127.255.254"))
        assertTrue(ConnectionDoctor.isTailscaleHost("mac.tail1234.ts.net"))
        assertTrue(ConnectionDoctor.isTailscaleHost("[fd7a:115c:a1e0::1]"))
        assertFalse(ConnectionDoctor.isTailscaleHost("100.128.0.1"))
        assertFalse(ConnectionDoctor.isTailscaleHost("100.63.0.1"))
        assertFalse(ConnectionDoctor.isTailscaleHost("192.168.1.10"))
        assertFalse(ConnectionDoctor.isTailscaleHost("127.0.0.1"))
        assertFalse(ConnectionDoctor.isTailscaleHost("example.com"))
    }

    @Test
    fun spotsALoginRefusal() {
        assertEquals(DiagnosisKind.WRONG_PASSWORD, ConnectionDoctor.classifyHttpError("HTTP 401 Unauthorized"))
        assertNull(ConnectionDoctor.classifyHttpError("Failed to connect to /100.101.102.103:4096"))
        assertNull(ConnectionDoctor.classifyHttpError("HTTP 4010"))
        assertNull(ConnectionDoctor.classifyHttpError(null))
    }
}
