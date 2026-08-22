package com.yang136.sshhelper.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AuthType { PASSWORD, PRIVATE_KEY }

enum class ProxyType { HTTP, SOCKS5 }

@Entity(
    tableName = "hosts",
    foreignKeys = [ForeignKey(
        entity = HostEntity::class,
        parentColumns = ["id"],
        childColumns = ["jumpHostId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["jumpHostId"])],
)
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType,
    val rememberCredential: Boolean,
    val privateKeyName: String? = null,
    @ColumnInfo(defaultValue = "0") val autoReconnect: Boolean = false,
    val jumpHostId: Long? = null,
    val proxyType: ProxyType? = null,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    val proxyUsername: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long? = null,
)

@Entity(
    tableName = "secrets",
    foreignKeys = [ForeignKey(
        entity = HostEntity::class,
        parentColumns = ["id"],
        childColumns = ["hostId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["hostId"], unique = true)],
)
data class SecretEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long,
    val credentialIv: ByteArray,
    val credentialCiphertext: ByteArray,
    val passphraseIv: ByteArray? = null,
    val passphraseCiphertext: ByteArray? = null,
    val proxyIv: ByteArray? = null,
    val proxyCiphertext: ByteArray? = null,
    @ColumnInfo(defaultValue = "1") val encryptionVersion: Int = 1,
)

@Entity(tableName = "vault_metadata")
data class VaultMetadataEntity(
    @PrimaryKey val id: Int = 1,
    val enabled: Boolean = false,
    val wrappedKeyIv: ByteArray? = null,
    val wrappedKeyCiphertext: ByteArray? = null,
    val migrationState: String = "DISABLED",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "known_hosts",
    indices = [Index(value = ["hostname", "port"], unique = true)],
)
data class KnownHostEntity(
    @PrimaryKey val id: String,
    val hostname: String,
    val port: Int,
    val keyType: String,
    val keyBase64: String,
    val fingerprintSha256: String,
    val trustedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "command_snippets",
    foreignKeys = [ForeignKey(
        entity = HostEntity::class,
        parentColumns = ["id"],
        childColumns = ["hostId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("hostId")],
)
data class CommandSnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val command: String,
    val groupName: String = "常用",
    val hostId: Long? = null,
    val executeImmediately: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "local_roots")
data class LocalRootEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val sortOrder: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "sftp_bookmarks",
    foreignKeys = [ForeignKey(
        entity = HostEntity::class,
        parentColumns = ["id"],
        childColumns = ["hostId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("hostId")],
)
data class SftpBookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long,
    val path: String,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class TransferDirection { UPLOAD, DOWNLOAD, REMOTE_COPY }
enum class TransferStatus { QUEUED, RUNNING, PAUSED, WAITING_NETWORK, WAITING_UNLOCK, COMPLETED, FAILED, CANCELLED }
enum class ConflictPolicy { ASK, OVERWRITE, SKIP, RENAME, RESUME }

enum class ForwardType { LOCAL, REMOTE, DYNAMIC }

@Entity(
    tableName = "port_forward_rules",
    foreignKeys = [ForeignKey(
        entity = HostEntity::class,
        parentColumns = ["id"],
        childColumns = ["hostId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("hostId")],
)
data class PortForwardRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long,
    val name: String,
    val type: ForwardType,
    val bindAddress: String,
    val listenPort: Int,
    val targetHost: String? = null,
    val targetPort: Int? = null,
    val autoStart: Boolean = false,
)

@Entity(tableName = "transfer_batches")
data class TransferBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "transfer_jobs",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TransferBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("hostId"), Index("batchId"), Index("status")],
)
data class TransferJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    val hostId: Long,
    val direction: TransferDirection,
    val source: String,
    val destination: String,
    val temporaryPath: String? = null,
    val totalBytes: Long = -1,
    val transferredBytes: Long = 0,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
    val status: TransferStatus = TransferStatus.QUEUED,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class CommandSnippet(
    val id: Long = 0,
    val title: String,
    val command: String,
    val groupName: String = "常用",
    val hostId: Long? = null,
    val executeImmediately: Boolean = false,
    val sortOrder: Int = 0,
)

data class HostProfile(
    val id: Long = 0,
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType,
    val rememberCredential: Boolean = false,
    val privateKeyName: String? = null,
    val autoReconnect: Boolean = false,
    val jumpHostId: Long? = null,
    val proxyType: ProxyType? = null,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    val proxyUsername: String? = null,
)

sealed interface Credential {
    data class Password(val value: CharArray) : Credential
    data class PrivateKey(
        val bytes: ByteArray,
        val passphrase: CharArray? = null,
        val fileName: String? = null,
    ) : Credential
}

fun HostEntity.toProfile() = HostProfile(
    id, name, hostname, port, username, authType, rememberCredential, privateKeyName,
    autoReconnect, jumpHostId, proxyType, proxyHost, proxyPort, proxyUsername,
)

fun HostProfile.toEntity(existing: HostEntity? = null) = HostEntity(
    id = id,
    name = name.trim(),
    hostname = hostname.trim(),
    port = port,
    username = username.trim(),
    authType = authType,
    rememberCredential = rememberCredential,
    privateKeyName = privateKeyName,
    autoReconnect = autoReconnect,
    jumpHostId = jumpHostId,
    proxyType = proxyType,
    proxyHost = proxyHost?.trim()?.takeIf(String::isNotEmpty),
    proxyPort = proxyPort,
    proxyUsername = proxyUsername?.trim()?.takeIf(String::isNotEmpty),
    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
    updatedAt = System.currentTimeMillis(),
    lastConnectedAt = existing?.lastConnectedAt,
)

/**
 * Validates the single-layer jump route of [host] against the full host list. Enforces:
 * no self-reference, no deleted jump target, jump host must itself be a plain connection
 * (never another jump), and a host already used as a jump by others cannot gain a jump.
 */
fun validateJumpRoute(host: HostProfile, allHosts: List<HostProfile>): String? {
    val jumpId = host.jumpHostId ?: return null
    if (jumpId == host.id) return "不能选择自己作为跳板机"
    val jump = allHosts.firstOrNull { it.id == jumpId }
    if (jump == null) return "跳板机不存在，请重新选择"
    if (jump.jumpHostId != null) return "仅支持一层跳板：跳板机本身不能再配置跳板"
    if (allHosts.any { it.jumpHostId == host.id }) return "该主机正被其他主机用作跳板机，不能再配置跳板"
    return null
}

fun CommandSnippetEntity.toModel() = CommandSnippet(
    id, title, command, groupName, hostId, executeImmediately, sortOrder,
)

fun CommandSnippet.toEntity(existing: CommandSnippetEntity? = null) = CommandSnippetEntity(
    id = id,
    title = title.trim(),
    command = command,
    groupName = groupName.trim().ifEmpty { "常用" },
    hostId = hostId,
    executeImmediately = executeImmediately && !command.contains('\n'),
    sortOrder = sortOrder,
    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
    updatedAt = System.currentTimeMillis(),
)

fun CommandSnippet.validationError(): String? = when {
    title.isBlank() -> "请输入命令名称"
    command.isBlank() -> "请输入命令内容"
    title.length > 60 -> "命令名称不能超过 60 个字符"
    command.length > 16_384 -> "命令内容不能超过 16KB"
    else -> null
}

fun HostProfile.validationError(): String? = when {
    name.isBlank() -> "请输入连接名称"
    hostname.isBlank() -> "请输入服务器地址"
    port !in 1..65535 -> "端口必须在 1 到 65535 之间"
    username.isBlank() -> "请输入用户名"
    else -> null
}

/** Validates the connection proxy settings; returns null when no proxy is configured. */
fun validateProxy(profile: HostProfile): String? {
    val type = profile.proxyType ?: return null
    if (profile.proxyHost.isNullOrBlank()) return "请输入代理服务器地址"
    if (profile.proxyPort == null || profile.proxyPort !in 1..65535) return "代理端口必须在 1–65535 之间"
    return null
}
