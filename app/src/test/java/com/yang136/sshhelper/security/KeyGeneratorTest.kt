package com.yang136.sshhelper.security

import com.jcraft.jsch.JSch
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyGeneratorTest {
    @Test
    fun generatesEd25519PublicKeyWithFingerprint() {
        val pair = KeyGenerator.generateEd25519("test@host")
        assertTrue("公钥应以 ssh-ed25519 开头，实际：${pair.publicKey.take(40)}", pair.publicKey.startsWith("ssh-ed25519 "))
        assertTrue("公钥应含注释", pair.publicKey.endsWith(" test@host"))
        assertTrue("指纹应为 SHA256 前缀，实际：${pair.fingerprint}", pair.fingerprint.startsWith("SHA256:"))
        assertTrue(pair.privateKey.isNotEmpty())
        assertTrue(pair.privateKey.toString(Charsets.UTF_8).contains("BEGIN OPENSSH PRIVATE KEY"))
    }

    @Test
    fun opensshPrivateKeyLoadsInJsch() {
        val pair = KeyGenerator.generateEd25519("test@host")
        val jsch = JSch()
        jsch.addIdentity("generated", pair.privateKey, null, null)
        assertTrue(jsch.identityRepository.identities.isNotEmpty())
    }

    @Test
    fun generatedKeyPairCanSign() {
        val pair = KeyGenerator.generateEd25519("test@host")
        val jsch = JSch()
        jsch.addIdentity("generated", pair.privateKey, null, null)
        val identity = jsch.identityRepository.identities.first()
        val challenge = "challenge-bytes".toByteArray()
        val signature = identity.getSignature(challenge)
        assertTrue("签名应非空", signature != null && signature.size > 0)
    }
}
