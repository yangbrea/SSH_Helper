package com.yang136.sshhelper.ssh

import com.jcraft.jsch.Logger
import com.yang136.sshhelper.diagnosticlog.DiagnosticEventLevel
import com.yang136.sshhelper.diagnosticlog.DiagnosticEventStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JschDiagnosticLoggerTest {
    @Test fun classifiesNegotiationAuthenticationAndDisconnect() {
        assertEquals(DiagnosticEventStage.SSH_VERSION, classifyJschLog(Logger.INFO, "Remote version string: SSH-2.0-OpenSSH_9.9")?.stage)
        assertEquals(DiagnosticEventStage.KEX, classifyJschLog(Logger.INFO, "kex: algorithm: curve25519-sha256")?.stage)
        assertEquals(DiagnosticEventStage.AUTH, classifyJschLog(Logger.INFO, "Authentication succeeded (publickey).")?.stage)
        assertEquals(DiagnosticEventStage.DISCONNECT, classifyJschLog(Logger.WARN, "SSH_MSG_DISCONNECT: 11 Bye")?.stage)
        assertEquals(DiagnosticEventLevel.WARNING, classifyJschLog(Logger.WARN, "SSH_MSG_DISCONNECT: 11 Bye")?.level)
    }

    @Test fun ignoresNoisyUnrelatedLibraryMessages() {
        assertNull(classifyJschLog(Logger.DEBUG, "some unrelated packet detail"))
    }
}
