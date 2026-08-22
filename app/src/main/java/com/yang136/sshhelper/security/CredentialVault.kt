package com.yang136.sshhelper.security

import android.content.Context
import android.os.Build
import android.app.KeyguardManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.room.withTransaction
import com.yang136.sshhelper.data.AppDatabase
import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.SecretEntity
import com.yang136.sshhelper.data.VaultMetadataEntity
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface VaultState {
    data object Disabled : VaultState
    data object Locked : VaultState
    data class Unlocked(val backgroundDeadline: Long? = null) : VaultState
    data class Unavailable(val reason: String) : VaultState
}

enum class VaultAction { ENABLE, UNLOCK, DISABLE }

sealed interface VaultResult {
    data object Success : VaultResult
    data class AuthenticationRequired(val action: VaultAction) : VaultResult
    data class Failure(val message: String) : VaultResult
}

class VaultLockedException : IllegalStateException("凭据保险库已锁定")

interface CredentialVault {
    val state: StateFlow<VaultState>
    fun canAuthenticate(): Boolean
    suspend fun enable(): VaultResult
    suspend fun unlock(): VaultResult
    suspend fun disable(): VaultResult
    suspend fun completeAuthentication(action: VaultAction): VaultResult
    suspend fun clearUnavailableCredentials(): VaultResult
    fun lock()
    fun onAppBackgrounded()
    fun onAppForegrounded()

    fun encrypt(hostId: Long, authType: AuthType, purpose: String, plain: ByteArray): Pair<EncryptedValue, Int>
    fun decrypt(hostId: Long, authType: AuthType, purpose: String, value: EncryptedValue, version: Int): ByteArray
}

class AndroidCredentialVault(
    context: Context,
    private val database: AppDatabase,
    private val legacyStore: SecretStore = AndroidKeystoreSecretStore(),
) : CredentialVault {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val mutableState = MutableStateFlow<VaultState>(VaultState.Disabled)
    override val state: StateFlow<VaultState> = mutableState.asStateFlow()
    @Volatile private var dataKeyBytes: ByteArray? = null
    @Volatile private var backgroundGeneration = 0L

    init {
        scope.launch {
            runCatching { database.vaultMetadataDao().get() }
                .onSuccess { metadata -> mutableState.value = if (metadata?.enabled == true) VaultState.Locked else VaultState.Disabled }
                .onFailure { mutableState.value = VaultState.Unavailable(it.message ?: "无法读取保险库状态") }
        }
    }

    override fun canAuthenticate(): Boolean {
        val secureScreen = appContext.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        val strongBiometric = BiometricManager.from(appContext).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
        ) == BiometricManager.BIOMETRIC_SUCCESS
        return secureScreen || strongBiometric
    }

    override suspend fun enable(): VaultResult = mutex.withLock {
        if (state.value !is VaultState.Disabled) return@withLock VaultResult.Failure("保险库已经启用")
        if (!canAuthenticate()) return@withLock VaultResult.Failure("请先在系统中设置指纹或安全锁屏")
        return@withLock runCatching { wrappingKey() }
            .fold({ VaultResult.AuthenticationRequired(VaultAction.ENABLE) }, { VaultResult.Failure(it.toVaultMessage()) })
    }

    override suspend fun unlock(): VaultResult = mutex.withLock {
        when (state.value) {
            is VaultState.Unlocked -> VaultResult.Success
            VaultState.Locked -> if (canAuthenticate()) {
                runCatching { wrappingKey() }
                    .fold({ VaultResult.AuthenticationRequired(VaultAction.UNLOCK) }, { VaultResult.Failure(it.toVaultMessage()) })
            } else {
                mutableState.value = VaultState.Unavailable("系统安全锁屏已移除或系统认证不可用")
                VaultResult.Failure("系统安全锁屏已移除或系统认证不可用")
            }
            VaultState.Disabled -> VaultResult.Success
            is VaultState.Unavailable -> VaultResult.Failure((state.value as VaultState.Unavailable).reason)
        }
    }

    override suspend fun disable(): VaultResult = mutex.withLock {
        when (state.value) {
            VaultState.Disabled -> VaultResult.Success
            is VaultState.Unavailable -> VaultResult.Failure((state.value as VaultState.Unavailable).reason)
            else -> if (canAuthenticate()) VaultResult.AuthenticationRequired(VaultAction.DISABLE)
            else VaultResult.Failure("系统认证当前不可用")
        }
    }

    override suspend fun completeAuthentication(action: VaultAction): VaultResult = mutex.withLock {
        runCatching {
            when (action) {
                VaultAction.ENABLE -> enableAuthenticated()
                VaultAction.UNLOCK -> unlockAuthenticated()
                VaultAction.DISABLE -> disableAuthenticated()
            }
            VaultResult.Success
        }.getOrElse { error ->
            if (action != VaultAction.ENABLE) lockInternal()
            val message = error.toVaultMessage()
            if (error is java.security.InvalidKeyException || error is android.security.keystore.KeyPermanentlyInvalidatedException) {
                mutableState.value = VaultState.Unavailable(message)
            }
            VaultResult.Failure(message)
        }
    }

    override suspend fun clearUnavailableCredentials(): VaultResult = mutex.withLock {
        if (state.value !is VaultState.Unavailable) return@withLock VaultResult.Failure("保险库当前可用，无需清除")
        runCatching {
            database.withTransaction {
                database.secretDao().deleteAll()
                database.vaultMetadataDao().put(VaultMetadataEntity(enabled = false, migrationState = "RESET"))
            }
            lockInternal()
            runCatching { keyStore.deleteEntry(WRAP_ALIAS) }
            mutableState.value = VaultState.Disabled
            VaultResult.Success
        }.getOrElse { VaultResult.Failure(it.toVaultMessage()) }
    }

    override fun lock() {
        if (state.value is VaultState.Unlocked) {
            lockInternal()
            mutableState.value = VaultState.Locked
        }
    }

    override fun onAppBackgrounded() {
        if (state.value !is VaultState.Unlocked) return
        val generation = ++backgroundGeneration
        val deadline = System.currentTimeMillis() + BACKGROUND_LOCK_MS
        mutableState.value = VaultState.Unlocked(deadline)
        scope.launch {
            delay(BACKGROUND_LOCK_MS)
            if (backgroundGeneration == generation) lock()
        }
    }

    override fun onAppForegrounded() {
        backgroundGeneration++
        if (state.value is VaultState.Unlocked) mutableState.value = VaultState.Unlocked(null)
    }

    override fun encrypt(
        hostId: Long,
        authType: AuthType,
        purpose: String,
        plain: ByteArray,
    ): Pair<EncryptedValue, Int> {
        val keyBytes = dataKeyBytes
        return if (state.value is VaultState.Disabled) {
            legacyStore.encrypt(plain) to LEGACY_VERSION
        } else {
            if (keyBytes == null) throw VaultLockedException()
            AesGcmCipher.encrypt(SecretKeySpec(keyBytes, "AES"), plain, aad(hostId, authType, purpose, VAULT_VERSION)) to VAULT_VERSION
        }
    }

    override fun decrypt(
        hostId: Long,
        authType: AuthType,
        purpose: String,
        value: EncryptedValue,
        version: Int,
    ): ByteArray = if (version == VAULT_VERSION) {
        val keyBytes = dataKeyBytes ?: throw VaultLockedException()
        AesGcmCipher.decrypt(SecretKeySpec(keyBytes, "AES"), value, aad(hostId, authType, purpose, version))
    } else {
        legacyStore.decrypt(value)
    }

    private suspend fun enableAuthenticated() {
        check(state.value is VaultState.Disabled) { "保险库状态已经变化" }
        val rawKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val wrapped = AesGcmCipher.encrypt(wrappingKey(), rawKey, WRAP_AAD)
        database.withTransaction {
            val dao = database.secretDao()
            dao.getAll().forEach { secret ->
                val host = database.hostDao().get(secret.hostId) ?: return@forEach
                val primary = legacyStore.decrypt(EncryptedValue(secret.credentialIv, secret.credentialCiphertext))
                val secondary = if (secret.passphraseIv != null && secret.passphraseCiphertext != null) {
                    legacyStore.decrypt(EncryptedValue(secret.passphraseIv, secret.passphraseCiphertext))
                } else null
                val primaryEncrypted = AesGcmCipher.encrypt(
                    SecretKeySpec(rawKey, "AES"), primary, aad(secret.hostId, host.authType, "primary", VAULT_VERSION),
                )
                val secondaryEncrypted = secondary?.let {
                    AesGcmCipher.encrypt(SecretKeySpec(rawKey, "AES"), it, aad(secret.hostId, host.authType, "passphrase", VAULT_VERSION))
                }
                primary.fill(0)
                secondary?.fill(0)
                dao.insert(secret.copy(
                    credentialIv = primaryEncrypted.iv,
                    credentialCiphertext = primaryEncrypted.ciphertext,
                    passphraseIv = secondaryEncrypted?.iv,
                    passphraseCiphertext = secondaryEncrypted?.ciphertext,
                    encryptionVersion = VAULT_VERSION,
                ))
            }
            database.vaultMetadataDao().put(VaultMetadataEntity(
                enabled = true,
                wrappedKeyIv = wrapped.iv,
                wrappedKeyCiphertext = wrapped.ciphertext,
                migrationState = "ENABLED",
            ))
        }
        replaceDataKey(rawKey)
        mutableState.value = VaultState.Unlocked(null)
    }

    private suspend fun unlockAuthenticated() {
        val metadata = database.vaultMetadataDao().get() ?: error("缺少保险库元数据")
        check(metadata.enabled && metadata.wrappedKeyIv != null && metadata.wrappedKeyCiphertext != null) { "保险库元数据不完整" }
        val raw = AesGcmCipher.decrypt(
            wrappingKey(), EncryptedValue(metadata.wrappedKeyIv, metadata.wrappedKeyCiphertext), WRAP_AAD,
        )
        replaceDataKey(raw)
        mutableState.value = VaultState.Unlocked(null)
    }

    private suspend fun disableAuthenticated() {
        if (dataKeyBytes == null) unlockAuthenticated()
        val current = dataKeyBytes?.copyOf() ?: error("无法解锁保险库")
        database.withTransaction {
            val dao = database.secretDao()
            dao.getAll().forEach { secret ->
                if (secret.encryptionVersion != VAULT_VERSION) return@forEach
                val host = database.hostDao().get(secret.hostId) ?: return@forEach
                val primary = AesGcmCipher.decrypt(
                    SecretKeySpec(current, "AES"), EncryptedValue(secret.credentialIv, secret.credentialCiphertext),
                    aad(secret.hostId, host.authType, "primary", VAULT_VERSION),
                )
                val secondary = if (secret.passphraseIv != null && secret.passphraseCiphertext != null) {
                    AesGcmCipher.decrypt(
                        SecretKeySpec(current, "AES"), EncryptedValue(secret.passphraseIv, secret.passphraseCiphertext),
                        aad(secret.hostId, host.authType, "passphrase", VAULT_VERSION),
                    )
                } else null
                val primaryEncrypted = legacyStore.encrypt(primary)
                val secondaryEncrypted = secondary?.let(legacyStore::encrypt)
                primary.fill(0)
                secondary?.fill(0)
                dao.insert(secret.copy(
                    credentialIv = primaryEncrypted.iv,
                    credentialCiphertext = primaryEncrypted.ciphertext,
                    passphraseIv = secondaryEncrypted?.iv,
                    passphraseCiphertext = secondaryEncrypted?.ciphertext,
                    encryptionVersion = LEGACY_VERSION,
                ))
            }
            database.vaultMetadataDao().put(VaultMetadataEntity(enabled = false, migrationState = "DISABLED"))
        }
        current.fill(0)
        lockInternal()
        mutableState.value = VaultState.Disabled
    }

    private fun wrappingKey(): SecretKey {
        (keyStore.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val builder = KeyGenParameterSpec.Builder(
            WRAP_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                30,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(30)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(builder.build())
            generateKey()
        }
    }

    private fun replaceDataKey(value: ByteArray) {
        dataKeyBytes?.fill(0)
        dataKeyBytes = value
    }

    private fun lockInternal() {
        dataKeyBytes?.fill(0)
        dataKeyBytes = null
    }

    private companion object {
        const val WRAP_ALIAS = "ssh_helper_vault_wrap_v2"
        const val LEGACY_VERSION = 1
        const val VAULT_VERSION = 2
        const val BACKGROUND_LOCK_MS = 5 * 60 * 1000L
        val WRAP_AAD = "SSH Helper credential vault v2".encodeToByteArray()

        fun aad(hostId: Long, authType: AuthType, purpose: String, version: Int): ByteArray =
            "$hostId|${authType.name}|$purpose|$version".encodeToByteArray()
    }
}

private fun Throwable.toVaultMessage(): String = when (this) {
    is android.security.keystore.UserNotAuthenticatedException -> "系统认证已过期，请重新验证"
    is android.security.keystore.KeyPermanentlyInvalidatedException -> "系统锁屏发生变化，保险库密钥已失效"
    is javax.crypto.AEADBadTagException -> "保险库数据校验失败，凭据可能已损坏"
    else -> message ?: "凭据保险库操作失败"
}
