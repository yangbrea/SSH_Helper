package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.data.Credential
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerPolicyTest {
    @Test
    fun sessionNamesAndLimitsAreStable() {
        assertEquals("主机", sessionDisplayName("主机", 1))
        assertEquals("主机 2", sessionDisplayName("主机", 2))
        assertEquals(8, MAX_MANAGED_SESSIONS)
        assertArrayEquals(intArrayOf(2, 5, 10), AUTO_RECONNECT_DELAYS_SECONDS)
        // Forwarding tunnels retry indefinitely with capped backoff until they come back.
        assertArrayEquals(intArrayOf(2, 5, 10, 30, 60, 120, 300), FORWARD_RECONNECT_DELAYS_SECONDS)
    }

    @Test
    fun clearingCredentialsOverwritesSensitiveArrays() {
        val password = Credential.Password(charArrayOf('s', 'e', 'c'))
        val key = Credential.PrivateKey(byteArrayOf(1, 2, 3), charArrayOf('p'))
        clearCredential(password)
        clearCredential(key)
        assertArrayEquals(charArrayOf('\u0000', '\u0000', '\u0000'), password.value)
        assertArrayEquals(byteArrayOf(0, 0, 0), key.bytes)
        assertArrayEquals(charArrayOf('\u0000'), key.passphrase)
    }

    @Test
    fun reconnectOnlyHandlesUnexpectedTransportFailures() {
        assertEquals(15_000, SSH_CONNECT_TIMEOUT_MS)
        assertEquals(20_000, SSH_KEEPALIVE_INTERVAL_MS)
        assertEquals(3, SSH_KEEPALIVE_MAX_MISSES)
        assertEquals(true, DisconnectCause.TRANSPORT_CLOSED.isAutoReconnectEligible())
        assertEquals(true, DisconnectCause.KEEPALIVE_TIMEOUT.isAutoReconnectEligible())
        assertEquals(false, DisconnectCause.REMOTE_SHELL_EXIT.isAutoReconnectEligible())
        assertEquals(false, DisconnectCause.USER.isAutoReconnectEligible())
    }

    @Test
    fun forwardSessionMonitoringAndHostKeyTimeoutsAreBounded() {
        assertEquals(5_000L, TRANSPORT_WATCH_INTERVAL_MS)
        assertEquals(60_000L, HOST_KEY_CONFIRM_TIMEOUT_MS)
        // 重连退避抖动保持在 ±20% 内。
        repeat(100) {
            assertTrue("backoff 抖动越界: ${backoffMillis(10)}", backoffMillis(10) in 8_000L..12_000L)
        }
    }
}
