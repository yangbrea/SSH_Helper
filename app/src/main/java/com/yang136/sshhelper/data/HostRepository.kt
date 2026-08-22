package com.yang136.sshhelper.data

import com.yang136.sshhelper.security.EncryptedValue
import com.yang136.sshhelper.security.CredentialVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class HostRepository(
    private val database: AppDatabase,
    private val credentialVault: CredentialVault,
) {
    val hosts: Flow<List<HostProfile>> = database.hostDao().observeAll().map { list ->
        list.map(HostEntity::toProfile)
    }

    suspend fun getHost(id: Long): HostProfile? = database.hostDao().get(id)?.toProfile()

    suspend fun save(profile: HostProfile, credential: Credential?): Long {
        val dao = database.hostDao()
        val existing = if (profile.id == 0L) null else dao.get(profile.id)
        val id = if (existing == null) dao.insert(profile.toEntity()) else {
            dao.update(profile.toEntity(existing))
            profile.id
        }
        if (existing != null && existing.authType != profile.authType && credential == null) {
            database.secretDao().deleteForHost(id)
        }
        if (!profile.rememberCredential) {
            database.secretDao().deleteForHost(id)
        } else if (credential != null) {
            val primary: ByteArray
            val secondary: ByteArray?
            when (credential) {
                is Credential.Password -> {
                    primary = credential.value.concatToString().encodeToByteArray()
                    secondary = null
                }
                is Credential.PrivateKey -> {
                    primary = credential.bytes.copyOf()
                    secondary = credential.passphrase?.concatToString()?.encodeToByteArray()
                }
            }
            val encryptedPrimary: EncryptedValue
            val encryptedSecondary: EncryptedValue?
            val encryptionVersion: Int
            try {
                val primaryResult = credentialVault.encrypt(id, profile.authType, "primary", primary)
                encryptedPrimary = primaryResult.first
                encryptionVersion = primaryResult.second
                encryptedSecondary = secondary?.let { credentialVault.encrypt(id, profile.authType, "passphrase", it).first }
            } finally {
                primary.fill(0)
                secondary?.fill(0)
            }
            database.secretDao().deleteForHost(id)
            database.secretDao().insert(
                SecretEntity(
                    hostId = id,
                    credentialIv = encryptedPrimary.iv,
                    credentialCiphertext = encryptedPrimary.ciphertext,
                    passphraseIv = encryptedSecondary?.iv,
                    passphraseCiphertext = encryptedSecondary?.ciphertext,
                    encryptionVersion = encryptionVersion,
                )
            )
        }
        return id
    }

    suspend fun credentialFor(profile: HostProfile): Credential? {
        val secret = database.secretDao().getForHost(profile.id) ?: return null
        val primary = credentialVault.decrypt(
            profile.id, profile.authType, "primary",
            EncryptedValue(secret.credentialIv, secret.credentialCiphertext), secret.encryptionVersion,
        )
        return when (profile.authType) {
            AuthType.PASSWORD -> {
                val chars = primary.decodeToString().toCharArray()
                primary.fill(0)
                Credential.Password(chars)
            }
            AuthType.PRIVATE_KEY -> {
                val passphrase = if (secret.passphraseIv != null && secret.passphraseCiphertext != null) {
                    val bytes = credentialVault.decrypt(
                        profile.id, profile.authType, "passphrase",
                        EncryptedValue(secret.passphraseIv, secret.passphraseCiphertext), secret.encryptionVersion,
                    )
                    bytes.decodeToString().toCharArray().also { bytes.fill(0) }
                } else null
                Credential.PrivateKey(primary, passphrase, profile.privateKeyName)
            }
        }
    }

    suspend fun delete(profile: HostProfile) {
        val dependents = database.hostDao().jumpDependentCount(profile.id)
        if (dependents > 0) {
            throw IllegalStateException("仍有 $dependents 台主机使用“${profile.name}”作为跳板机，请先解除引用")
        }
        database.hostDao().get(profile.id)?.let { database.hostDao().delete(it) }
    }

    suspend fun markConnected(id: Long) = database.hostDao().markConnected(id)
}
