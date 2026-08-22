package com.yang136.sshhelper.ssh

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKeyPolicyTest {
    private val key = "server-public-key".encodeToByteArray()

    @Test fun missingKeyIsUnknown() {
        assertEquals(HostKeyMatch.UNKNOWN, compareHostKey(null, key))
    }

    @Test fun sameKeyMatches() {
        val encoded = Base64.getEncoder().encodeToString(key)
        assertEquals(HostKeyMatch.MATCH, compareHostKey(encoded, key))
    }

    @Test fun differentKeyIsChanged() {
        val encoded = Base64.getEncoder().encodeToString(key)
        assertEquals(HostKeyMatch.CHANGED, compareHostKey(encoded, "attacker-key".encodeToByteArray()))
    }

    @Test fun fingerprintUsesOpenSshSha256Shape() {
        assertTrue(sha256Fingerprint(key).startsWith("SHA256:"))
        assertTrue(!sha256Fingerprint(key).endsWith("="))
    }
}
