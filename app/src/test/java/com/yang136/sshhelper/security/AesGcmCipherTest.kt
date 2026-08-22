package com.yang136.sshhelper.security

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AesGcmCipherTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun encryptedValueRoundTrips() {
        val key = key()
        val plain = "sensitive-password".encodeToByteArray()
        val encrypted = AesGcmCipher.encrypt(key, plain)
        assertFalse(plain.contentEquals(encrypted.ciphertext))
        assertArrayEquals(plain, AesGcmCipher.decrypt(key, encrypted))
    }

    @Test(expected = AEADBadTagException::class)
    fun wrongKeyCannotDecrypt() {
        val encrypted = AesGcmCipher.encrypt(key(), "private-key".encodeToByteArray())
        AesGcmCipher.decrypt(key(), encrypted)
    }

    @Test(expected = AEADBadTagException::class)
    fun tamperedCiphertextIsRejected() {
        val key = key()
        val encrypted = AesGcmCipher.encrypt(key, "secret".encodeToByteArray())
        encrypted.ciphertext[0] = (encrypted.ciphertext[0].toInt() xor 1).toByte()
        AesGcmCipher.decrypt(key, encrypted)
    }

    @Test(expected = AEADBadTagException::class)
    fun credentialCannotBeMovedToDifferentHostOrPurpose() {
        val key = key()
        val encrypted = AesGcmCipher.encrypt(key, "secret".encodeToByteArray(), "1|PASSWORD|primary|2".encodeToByteArray())
        AesGcmCipher.decrypt(key, encrypted, "2|PASSWORD|primary|2".encodeToByteArray())
    }
}
