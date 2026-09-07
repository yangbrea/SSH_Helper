package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.ssh.native.NativeSshException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeSshErrorMappingTest {
    @Test
    fun mapsTransportClosedToDisconnectCause() {
        val error = NativeSshException(
            domain = "system",
            code = "transport_closed",
            message = "SSH transport closed",
            libssh2Code = -13,
            systemErrno = 0,
        )
        assertEquals(DisconnectCause.TRANSPORT_CLOSED, error.toDisconnectCause())
    }

    @Test
    fun mapsKeepaliveTimeoutToDisconnectCause() {
        val error = NativeSshException(
            domain = "timeout",
            code = "keepalive_timeout",
            message = "keepalive timed out",
            libssh2Code = 0,
            systemErrno = 0,
        )
        assertEquals(DisconnectCause.KEEPALIVE_TIMEOUT, error.toDisconnectCause())
    }

    @Test
    fun userMessageDoesNotExposeNumericCodeAndRedactsSecrets() {
        val error = NativeSshException(
            domain = "system",
            code = "keepalive_send_failed",
            message = "socket send failed password=secret",
            libssh2Code = -7,
            systemErrno = 0,
        )
        val message = error.toUserMessage()
        assertFalse(message.contains("-7"))
        assertFalse(message.contains("secret"))
    }

    @Test
    fun sanitizeStripsControlCharacters() {
        val clean = sanitizeNativeSshMessage("bad\u0007message")
        assertFalse(clean.contains('\u0007'))
    }
}
