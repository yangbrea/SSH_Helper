package com.yang136.sshhelper.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedValue(val iv: ByteArray, val ciphertext: ByteArray)

interface SecretStore {
    fun encrypt(plain: ByteArray, aad: ByteArray? = null): EncryptedValue
    fun decrypt(value: EncryptedValue, aad: ByteArray? = null): ByteArray
}

class AndroidKeystoreSecretStore : SecretStore {
    private val alias = "ssh_helper_credentials_v1"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    override fun encrypt(plain: ByteArray, aad: ByteArray?): EncryptedValue {
        return AesGcmCipher.encrypt(key(), plain, aad)
    }

    override fun decrypt(value: EncryptedValue, aad: ByteArray?): ByteArray {
        return AesGcmCipher.decrypt(key(), value, aad)
    }
}

internal object AesGcmCipher {
    fun encrypt(key: SecretKey, plain: ByteArray, aad: ByteArray? = null): EncryptedValue {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        if (aad != null) cipher.updateAAD(aad)
        return EncryptedValue(cipher.iv, cipher.doFinal(plain))
    }

    fun decrypt(key: SecretKey, value: EncryptedValue, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(value.ciphertext)
    }
}
