package com.yang136.sshhelper.documents

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import com.yang136.sshhelper.MainActivity
import com.yang136.sshhelper.R
import com.yang136.sshhelper.data.AppDatabase
import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.Credential
import com.yang136.sshhelper.data.DocumentRootEntity
import com.yang136.sshhelper.data.DocumentWritebackEntity
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.HostRepository
import com.yang136.sshhelper.security.AesGcmCipher
import com.yang136.sshhelper.security.EncryptedValue
import com.yang136.sshhelper.ssh.RouteCredentials
import com.yang136.sshhelper.ssh.SshRoute
import com.yang136.sshhelper.ssh.clearCredential
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AuthorizedDocumentRoute(
    val route: SshRoute,
    val credentials: RouteCredentials,
)

/**
 * Owns the explicit SAF grants.  Grant credentials are encrypted with a distinct Keystore key
 * so they remain available when the interactive credential vault locks, but access is denied
 * whenever the Android device itself is locked.
 */
class DocumentAccessManager(
    private val context: Context,
    private val database: AppDatabase,
    private val hosts: HostRepository,
) {
    private val appContext = context.applicationContext
    private val dao = database.documentAccessDao()
    private val cipher = AndroidDocumentGrantCipher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyguard = appContext.getSystemService(KeyguardManager::class.java)
    @Volatile var closeConnections: ((Long?) -> Unit)? = null
    @Volatile var retryWritebackHandler: (suspend (Long) -> Unit)? = null

    val roots: StateFlow<List<DocumentRootEntity>> = dao.observeRoots()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val writebacks: StateFlow<List<DocumentWritebackEntity>> = dao.observeWritebacks()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scope.launch {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) closeConnections?.invoke(null)
                notifyRootsChanged()
            }
        }
    }

    init {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        appContext.registerReceiver(
            lockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
    }

    fun isDeviceUnlocked(): Boolean = keyguard?.isDeviceLocked != true

    suspend fun isEnabled(hostId: Long): Boolean = dao.root(hostId) != null

    suspend fun enable(hostId: Long) {
        check(isDeviceUnlocked()) { "请先解锁设备" }
        val target = hosts.getHost(hostId) ?: error("主机不存在")
        require(target.rememberCredential) { "请先保存该主机的登录凭据" }
        requireTrusted(target)
        val jump = target.jumpHostId?.let { hosts.getHost(it) ?: error("跳板机不存在") }
        if (jump != null) {
            require(jump.rememberCredential) { "请先保存跳板机“${jump.name}”的登录凭据" }
            requireTrusted(jump)
        }
        val targetCredential = hosts.credentialFor(target) ?: error("该主机没有可用的已保存凭据")
        val jumpCredential = jump?.let { hosts.credentialFor(it) ?: error("跳板机没有可用的已保存凭据") }
        val routeCredentials = RouteCredentials(
            target = targetCredential,
            jump = jumpCredential,
            targetProxyPassword = if (jump == null) hosts.proxyPasswordFor(target) else null,
            jumpProxyPassword = jump?.let { hosts.proxyPasswordFor(it) },
        )
        val plain = DocumentCredentialCodec.encode(routeCredentials)
        try {
            val signature = routeSignature(target, jump)
            val encrypted = cipher.encrypt(plain, grantAad(hostId, signature))
            val old = dao.root(hostId)
            dao.putRoot(
                DocumentRootEntity(
                    hostId = hostId,
                    credentialIv = encrypted.iv,
                    credentialCiphertext = encrypted.ciphertext,
                    routeSignature = signature,
                    enabledAt = old?.enabledAt ?: System.currentTimeMillis(),
                ),
            )
        } finally {
            plain.fill(0)
            clearCredential(targetCredential)
            clearCredential(jumpCredential)
        }
        notifyRootsChanged()
    }

    suspend fun disable(hostId: Long) {
        // Failed recovery copies intentionally remain until the user handles them.
        dao.deleteRoot(hostId)
        closeConnections?.invoke(hostId)
        notifyRootsChanged()
    }

    /** Refresh every exposed route that directly uses [changedHostId] or uses it as a jump. */
    suspend fun refreshAffected(changedHostId: Long): List<Long> {
        val affected = dao.roots().mapNotNull { root ->
            val target = hosts.getHost(root.hostId) ?: return@mapNotNull root.hostId
            root.hostId.takeIf { it == changedHostId || target.jumpHostId == changedHostId }
        }
        val revoked = mutableListOf<Long>()
        affected.forEach { rootHostId ->
            runCatching { enable(rootHostId) }.onFailure {
                dao.deleteRoot(rootHostId)
                closeConnections?.invoke(rootHostId)
                revoked += rootHostId
            }
        }
        if (affected.isNotEmpty()) notifyRootsChanged()
        return revoked
    }

    suspend fun authorizedRoute(hostId: Long): AuthorizedDocumentRoute {
        check(isDeviceUnlocked()) { "设备已锁定" }
        val grant = dao.root(hostId) ?: error("该主机未授权系统文件访问")
        check(grant.credentialVersion == 1) { "不支持的系统文件凭据版本，请重新授权" }
        val target = hosts.getHost(hostId) ?: error("主机不存在")
        val jump = target.jumpHostId?.let { hosts.getHost(it) ?: error("跳板机不存在") }
        requireTrusted(target)
        jump?.let { requireTrusted(it) }
        val signature = routeSignature(target, jump)
        if (!MessageDigest.isEqual(signature.encodeToByteArray(), grant.routeSignature.encodeToByteArray())) {
            val message = "主机配置已变化，请在 SSH Helper 中重新授权系统文件访问"
            notifyAccessProblem(hostId, message)
            error(message)
        }
        val plain = cipher.decrypt(
            EncryptedValue(grant.credentialIv, grant.credentialCiphertext),
            grantAad(hostId, signature),
        )
        val credentials = try {
            DocumentCredentialCodec.decode(plain)
        } finally {
            plain.fill(0)
        }
        return AuthorizedDocumentRoute(SshRoute(target, jump), credentials)
    }

    suspend fun addFailedWriteback(
        hostId: Long,
        remotePath: String,
        localPath: String,
        baselineSize: Long,
        baselineModifiedAt: Long,
        error: String,
    ): Long = dao.putWriteback(
        DocumentWritebackEntity(
            hostId = hostId,
            remotePath = remotePath,
            localPath = localPath,
            baselineSize = baselineSize,
            baselineModifiedAt = baselineModifiedAt,
            error = error,
        ),
    )

    suspend fun discardWriteback(id: Long) {
        val item = dao.writeback(id) ?: return
        java.io.File(item.localPath).delete()
        dao.deleteWriteback(id)
    }

    suspend fun retryWriteback(id: Long) {
        val handler = retryWritebackHandler ?: error("系统文件后端尚未就绪")
        handler(id)
    }

    suspend fun exportWriteback(id: Long, output: OutputStream) {
        val item = dao.writeback(id) ?: error("恢复副本不存在")
        val source = java.io.File(item.localPath)
        check(source.isFile) { "恢复副本不存在" }
        source.inputStream().use { input -> output.use { input.copyTo(it) } }
    }

    suspend fun removeWriteback(id: Long) = dao.deleteWriteback(id)

    private suspend fun requireTrusted(profile: HostProfile) {
        if (database.knownHostDao().find(profile.hostname, profile.port) == null) {
            val message = "请先连接“${profile.name}”并确认服务器指纹"
            notifyAccessProblem(profile.id, message)
            error(message)
        }
    }

    fun notifyRootsChanged() {
        appContext.contentResolver.notifyChange(
            DocumentsContract.buildRootsUri("${appContext.packageName}.documents"),
            null,
        )
    }

    fun notifyAccessProblem(hostId: Long, message: String) {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(ACCESS_CHANNEL, "系统文件访问", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val notificationId = (7400 + hostId % 1000).toInt()
        val open = PendingIntent.getActivity(
            appContext,
            notificationId,
            Intent(appContext, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                .putExtra(MainActivity.EXTRA_SETTINGS_SECTION, "documents"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            notificationId,
            NotificationCompat.Builder(appContext, ACCESS_CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("系统文件访问已拒绝")
                .setContentText(message)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object { const val ACCESS_CHANNEL = "document_access" }
}

internal fun routeSignature(target: HostProfile, jump: HostProfile?): String {
    fun HostProfile.part() = listOf(
        id, hostname, port, username, authType.name, privateKeyName.orEmpty(), jumpHostId ?: 0,
        proxyType?.name.orEmpty(), proxyHost.orEmpty(), proxyPort ?: 0, proxyUsername.orEmpty(),
    ).joinToString("\u0000")
    val source = target.part() + "\u0001" + jump?.part().orEmpty()
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(source.encodeToByteArray()),
    )
}

private fun grantAad(hostId: Long, signature: String) = "documents|$hostId|$signature|1".encodeToByteArray()

internal object DocumentCredentialCodec {
    private const val VERSION = 1

    fun encode(value: RouteCredentials): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            writeCredential(output, value.target)
            output.writeBoolean(value.jump != null)
            value.jump?.let { writeCredential(output, it) }
            writeSensitiveString(output, value.targetProxyPassword)
            writeSensitiveString(output, value.jumpProxyPassword)
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): RouteCredentials = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == VERSION) { "不支持的系统文件凭据版本" }
        val target = readCredential(input)
        val jump = if (input.readBoolean()) readCredential(input) else null
        val targetProxy = readNullable(input)?.let { raw -> raw.decodeToString().also { raw.fill(0) } }
        val jumpProxy = readNullable(input)?.let { raw -> raw.decodeToString().also { raw.fill(0) } }
        RouteCredentials(target, jump, targetProxy, jumpProxy)
    }

    private fun writeCredential(output: DataOutputStream, credential: Credential) {
        when (credential) {
            is Credential.Password -> {
                output.writeByte(1)
                val raw = credential.value.concatToString().encodeToByteArray()
                try { writeBytes(output, raw) } finally { raw.fill(0) }
            }
            is Credential.PrivateKey -> {
                output.writeByte(2)
                writeBytes(output, credential.bytes)
                val passphrase = credential.passphrase?.concatToString()?.encodeToByteArray()
                try { writeNullable(output, passphrase) } finally { passphrase?.fill(0) }
                writeNullable(output, credential.fileName?.encodeToByteArray())
            }
        }
    }

    private fun readCredential(input: DataInputStream): Credential = when (input.readUnsignedByte()) {
        1 -> readBytes(input).let { raw -> Credential.Password(raw.decodeToString().toCharArray()).also { raw.fill(0) } }
        2 -> {
            val key = readBytes(input)
            val passphrase = readNullable(input)?.let { raw -> raw.decodeToString().toCharArray().also { raw.fill(0) } }
            val name = readNullable(input)?.let { raw -> raw.decodeToString().also { raw.fill(0) } }
            Credential.PrivateKey(key, passphrase, name)
        }
        else -> error("损坏的系统文件凭据")
    }

    private fun writeNullable(output: DataOutputStream, value: ByteArray?) {
        output.writeBoolean(value != null)
        if (value != null) writeBytes(output, value)
    }

    private fun writeSensitiveString(output: DataOutputStream, value: String?) {
        val raw = value?.encodeToByteArray()
        try { writeNullable(output, raw) } finally { raw?.fill(0) }
    }

    private fun readNullable(input: DataInputStream): ByteArray? = if (input.readBoolean()) readBytes(input) else null

    private fun writeBytes(output: DataOutputStream, value: ByteArray) {
        require(value.size <= 16 * 1024 * 1024) { "凭据过大" }
        output.writeInt(value.size)
        output.write(value)
    }

    private fun readBytes(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 0..16 * 1024 * 1024) { "损坏的系统文件凭据长度" }
        return ByteArray(size).also(input::readFully)
    }
}

private class AndroidDocumentGrantCipher {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun encrypt(plain: ByteArray, aad: ByteArray): EncryptedValue = AesGcmCipher.encrypt(key(), plain, aad)
    fun decrypt(value: EncryptedValue, aad: ByteArray): ByteArray = AesGcmCipher.decrypt(key(), value, aad)

    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUnlockedDeviceRequired(true)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(builder.build())
            generateKey()
        }
    }

    private companion object { const val ALIAS = "ssh_helper_documents_v1" }
}
