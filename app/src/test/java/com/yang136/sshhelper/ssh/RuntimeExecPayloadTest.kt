package com.yang136.sshhelper.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeExecPayloadTest {
    @Test
    fun parsesExitAndStdoutOnly() {
        val result = parseRuntimeExecPayload("exit=0\nhello\n")
        assertEquals(0, result.exitCode)
        assertEquals("hello\n", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun parsesStderrMarkers() {
        val raw = "exit=1\nout-data\nSTDERR_BEGIN\nerr-data\nSTDERR_END\n"
        val result = parseRuntimeExecPayload(raw)
        assertEquals(1, result.exitCode)
        assertEquals("out-data", result.stdout)
        assertEquals("err-data", result.stderr)
    }

    @Test
    fun parsesTimeoutExitCode() {
        val result = parseRuntimeExecPayload("exit=124\n")
        assertEquals(REMOTE_COMMAND_TIMEOUT_EXIT_CODE, result.exitCode)
        assertEquals("", result.stdout)
        assertEquals("", result.stderr)
    }
}

class RuntimeTcpHandshakePayloadTest {
    @Test
    fun parsesHandshakeFields() {
        val raw = "fingerprint=SHA256:abc\nkeyType=ssh-ed25519\nkeyBase64=c2VjcmV0"
        val info = parseRuntimeTcpHandshakePayload(raw)
        assertEquals("SHA256:abc", info.fingerprint)
        assertEquals("ssh-ed25519", info.keyType)
        assertEquals("c2VjcmV0", info.keyBase64)
    }

    @Test
    fun acceptsFieldOrderReordered() {
        val raw = "keyBase64=c2VjcmV0\nkeyType=ssh-ed25519\nfingerprint=SHA256:abc\n"
        val info = parseRuntimeTcpHandshakePayload(raw)
        assertEquals("SHA256:abc", info.fingerprint)
        assertEquals("ssh-ed25519", info.keyType)
        assertEquals("c2VjcmV0", info.keyBase64)
    }
}
