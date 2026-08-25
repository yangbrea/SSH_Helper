package com.yang136.sshhelper.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardFailureGateTest {
    @Test
    fun authenticationFailureRemainsBlockedAcrossNetworkRecovery() {
        val gate = ForwardFailureGate()
        gate.block(7L, ForwardFailureKind.AUTH)

        assertEquals(emptyList<Long>(), gate.allowNetworkRetry())
        assertTrue(gate.isBlocked(7L))

        gate.allowExplicitRetry(7L)
        assertFalse(gate.isBlocked(7L))
    }

    @Test
    fun transientFailureIsReleasedExactlyOnceByNetworkRecovery() {
        val gate = ForwardFailureGate()
        gate.block(11L, ForwardFailureKind.TRANSIENT)

        assertEquals(listOf(11L), gate.allowNetworkRetry())
        assertEquals(emptyList<Long>(), gate.allowNetworkRetry())
        assertFalse(gate.isBlocked(11L))
    }

    @Test
    fun classifiesCredentialAndTransportFailuresConservatively() {
        assertEquals(ForwardFailureKind.AUTH, classifyForwardFailure("Auth fail"))
        assertEquals(ForwardFailureKind.AUTH, classifyForwardFailure("Permission denied"))
        assertEquals(ForwardFailureKind.AUTH, classifyForwardFailure("主机密钥已变化"))
        assertEquals(ForwardFailureKind.TRANSIENT, classifyForwardFailure("Connection timed out"))
    }
}
