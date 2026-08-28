package com.yang136.sshhelper.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 配置导出/导入：服务器配置（主机）与快捷指令，不含凭据与设置项。 */
const val CONFIG_EXPORT_APP = "ssh-helper"
const val CONFIG_EXPORT_KIND = "config-export"
const val CONFIG_EXPORT_VERSION = 1
const val CONFIG_IMPORT_MAX_CHARS = 2_000_000

/** 主机在导出文件中的表示（只含连接参数，绝不含密码/私钥等凭据）。 */
data class ExportHost(
    val id: Long,
    val name: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val authType: AuthType,
    val rememberCredential: Boolean,
    val privateKeyName: String?,
    val autoReconnect: Boolean,
    val jumpHostId: Long?,
    val proxyType: ProxyType?,
    val proxyHost: String?,
    val proxyPort: Int?,
    val proxyUsername: String?,
)

/** 快捷指令在导出文件中的表示。 */
data class ExportSnippet(
    val id: Long,
    val title: String,
    val command: String,
    val groupName: String,
    val hostId: Long?,
    val executeImmediately: Boolean,
    val sortOrder: Int,
)

data class ParsedExport(
    val hosts: List<ExportHost>,
    val snippets: List<ExportSnippet>,
)

data class ConfigExportResult(
    val json: String,
    val hostCount: Int,
    val snippetCount: Int,
)

/** 增量导入统计。 */
data class ImportReport(
    val addedHosts: Int,
    val updatedHosts: Int,
    val skippedHosts: Int,
    val addedSnippets: Int,
    val updatedSnippets: Int,
    val skippedSnippets: Int,
    val skippedReasons: List<String>,
) {
    fun summary(): String = buildString {
        append("主机：新增 $addedHosts · 更新 $updatedHosts")
        if (skippedHosts > 0) append(" · 跳过 $skippedHosts")
        append("；快捷指令：新增 $addedSnippets · 更新 $updatedSnippets")
        if (skippedSnippets > 0) append(" · 跳过 $skippedSnippets")
    }

    fun hasChanges(): Boolean = addedHosts > 0 || updatedHosts > 0 || addedSnippets > 0 || updatedSnippets > 0
}

/**
 * 单个主机的导入计划。entity 的 jumpHostId 恒为 null，跳板引用由
 * [jumpExportedRef]（导出文件中的目标主机 id）表达，落库时再解析为本地 id。
 */
data class PlannedHost(
    val exportedId: Long,
    val entity: HostEntity,
    val jumpExportedRef: Long?,
)

/**
 * 增量导入计划（纯数据，可 JVM 测试）：只增改、不删除既有数据。
 * hostId/jumpHostId 若引用待新增主机，先保留导出 id，由 [ConfigTransferManager] 解析。
 */
data class ImportPlan(
    val hostInserts: List<PlannedHost>,
    val hostUpdates: List<PlannedHost>,
    val secretDeletions: Set<Long>,
    val snippetInserts: List<CommandSnippetEntity>,
    val snippetUpdates: List<CommandSnippetEntity>,
    val report: ImportReport,
)

private fun ExportHost.toProfile(): HostProfile = HostProfile(
    id = 0,
    name = name,
    hostname = hostname,
    port = port,
    username = username,
    authType = authType,
    rememberCredential = rememberCredential,
    privateKeyName = privateKeyName,
    autoReconnect = autoReconnect,
    jumpHostId = null,
    proxyType = proxyType,
    proxyHost = proxyHost,
    proxyPort = proxyPort,
    proxyUsername = proxyUsername,
)

private fun ExportSnippet.toModel(): CommandSnippet = CommandSnippet(
    id = 0,
    title = title,
    command = command,
    groupName = groupName,
    hostId = hostId,
    executeImmediately = executeImmediately,
    sortOrder = sortOrder,
)

private fun hostKey(hostname: String, port: Int, username: String): String =
    "${hostname.trim().lowercase()}|$port|${username.trim().lowercase()}"

private fun snippetKey(title: String, command: String): String =
    "${title.trim()}|$command"

/**
 * 将当前主机与快捷指令序列化为版本化 JSON。
 * 凭据、设置项、known_hosts、文档授权等设备相关数据一律不导出。
 */
fun buildExportJson(hosts: List<HostEntity>, snippets: List<CommandSnippetEntity>): String {
    val hostArray = JSONArray()
    hosts.forEach { host ->
        hostArray.put(
            JSONObject()
                .put("id", host.id)
                .put("name", host.name)
                .put("hostname", host.hostname)
                .put("port", host.port)
                .put("username", host.username)
                .put("authType", host.authType.name)
                .put("rememberCredential", host.rememberCredential)
                .put("privateKeyName", host.privateKeyName ?: JSONObject.NULL)
                .put("autoReconnect", host.autoReconnect)
                .put("jumpHostId", host.jumpHostId ?: JSONObject.NULL)
                .put("proxyType", host.proxyType?.name ?: JSONObject.NULL)
                .put("proxyHost", host.proxyHost ?: JSONObject.NULL)
                .put("proxyPort", host.proxyPort ?: JSONObject.NULL)
                .put("proxyUsername", host.proxyUsername ?: JSONObject.NULL)
        )
    }
    val snippetArray = JSONArray()
    snippets.forEach { snippet ->
        snippetArray.put(
            JSONObject()
                .put("id", snippet.id)
                .put("title", snippet.title)
                .put("command", snippet.command)
                .put("groupName", snippet.groupName)
                .put("hostId", snippet.hostId ?: JSONObject.NULL)
                .put("executeImmediately", snippet.executeImmediately)
                .put("sortOrder", snippet.sortOrder)
        )
    }
    return JSONObject()
        .put("app", CONFIG_EXPORT_APP)
        .put("kind", CONFIG_EXPORT_KIND)
        .put("version", CONFIG_EXPORT_VERSION)
        .put("exportedAt", java.time.OffsetDateTime.now().toString())
        .put("hosts", hostArray)
        .put("snippets", snippetArray)
        .toString(2)
}

/** 解析导出文件，校验 envelope（app/kind/version）；字段缺失/非法时跳过对应条目。 */
fun parseExportJson(text: String): ParsedExport {
    val root = try {
        JSONObject(text)
    } catch (error: JSONException) {
        throw IllegalArgumentException("不是有效的 JSON 文件：${error.message ?: "格式错误"}")
    }
    if (root.optString("app") != CONFIG_EXPORT_APP || root.optString("kind") != CONFIG_EXPORT_KIND) {
        throw IllegalArgumentException("不是 SSH Helper 的配置导出文件")
    }
    val version = root.optInt("version", 0)
    if (version != CONFIG_EXPORT_VERSION) {
        throw IllegalArgumentException("不支持的导出文件版本：v$version（当前支持 v$CONFIG_EXPORT_VERSION）")
    }
    val hosts = mutableListOf<ExportHost>()
    (root.optJSONArray("hosts") ?: JSONArray()).forEachJson { item ->
        val authType = runCatching { AuthType.valueOf(item.optString("authType", "")) }.getOrNull() ?: return@forEachJson
        hosts += ExportHost(
            id = item.optLong("id", 0L),
            name = item.optString("name", ""),
            hostname = item.optString("hostname", ""),
            port = item.optInt("port", 0),
            username = item.optString("username", ""),
            authType = authType,
            rememberCredential = item.optBoolean("rememberCredential", false),
            privateKeyName = item.optString("privateKeyName", "").takeIf(String::isNotEmpty),
            autoReconnect = item.optBoolean("autoReconnect", false),
            jumpHostId = item.nullableLong("jumpHostId"),
            proxyType = item.optString("proxyType", "").takeIf(String::isNotEmpty)
                ?.let { runCatching { ProxyType.valueOf(it) }.getOrNull() },
            proxyHost = item.optString("proxyHost", "").takeIf(String::isNotEmpty),
            proxyPort = item.nullableLong("proxyPort")?.toInt(),
            proxyUsername = item.optString("proxyUsername", "").takeIf(String::isNotEmpty),
        )
    }
    val snippets = mutableListOf<ExportSnippet>()
    (root.optJSONArray("snippets") ?: JSONArray()).forEachJson { item ->
        snippets += ExportSnippet(
            id = item.optLong("id", 0L),
            title = item.optString("title", ""),
            command = item.optString("command", ""),
            groupName = item.optString("groupName", "常用"),
            hostId = item.nullableLong("hostId"),
            executeImmediately = item.optBoolean("executeImmediately", false),
            sortOrder = item.optInt("sortOrder", 0),
        )
    }
    return ParsedExport(hosts, snippets)
}

/**
 * 增量导入规划（纯函数）：主机按自然键 (hostname, port, username) 匹配，
 * 命中则更新（保留 id/时间戳/rememberCredential/已存凭据），未命中则新增
 * （rememberCredential=false、不写凭据）；快捷指令按 (title, command) 匹配。
 * 重复条目与非法条目跳过并计数。任何情况下都不删除既有数据。
 */
fun planIncrementalImport(
    parsed: ParsedExport,
    existingHosts: List<HostEntity>,
    existingSnippets: List<CommandSnippetEntity>,
): ImportPlan {
    val existingByKey = existingHosts.associateBy { hostKey(it.hostname, it.port, it.username) }
    val existingSnippetByKey = existingSnippets.associateBy { snippetKey(it.title, it.command) }

    // 第一遍：校验、去重、分类（新增/更新）。
    data class Classified(
        val exported: ExportHost,
        val isUpdate: Boolean,
        val existing: HostEntity?,
    )
    val classified = mutableListOf<Classified>()
    val seenKeys = mutableSetOf<String>()
    val reasons = mutableListOf<String>()
    var skippedHosts = 0
    parsed.hosts.forEach { exported ->
        val profile = exported.toProfile()
        val error = profile.validationError() ?: validateProxy(profile)
        if (error != null) {
            skippedHosts += 1
            reasons += "跳过主机 ${profile.name.ifBlank { profile.hostname }}：$error"
            return@forEach
        }
        val key = hostKey(profile.hostname, profile.port, profile.username)
        if (!seenKeys.add(key)) {
            skippedHosts += 1
            reasons += "跳过重复主机 ${profile.name}：文件内已有相同服务器（${profile.hostname}:${profile.port}@${profile.username}）"
            return@forEach
        }
        val existing = existingByKey[key]
        classified += Classified(exported, existing != null, existing)
    }

    // 第二遍：解析跳板引用（仅支持一层跳板；目标本身带跳板或指向自身则放弃）。
    val idByExported = classified.associate { it.exported.id to it }
    val hostInserts = mutableListOf<PlannedHost>()
    val hostUpdates = mutableListOf<PlannedHost>()
    val secretDeletions = mutableSetOf<Long>()
    var addedHosts = 0
    var updatedHosts = 0
    classified.forEach { entry ->
        val exported = entry.exported
        val profile = exported.toProfile()
        val ref = exported.jumpHostId
        val target = idByExported[ref]
        val jumpRef = when {
            ref == null || ref == exported.id -> null
            target != null && target.exported.jumpHostId != null -> null
            else -> ref
        }
        if (entry.isUpdate) {
            val existing = entry.existing!!
            hostUpdates += PlannedHost(
                exportedId = exported.id,
                entity = profile.copy(id = existing.id)
                    .toEntity(existing)
                    .copy(rememberCredential = existing.rememberCredential, jumpHostId = null),
                jumpExportedRef = jumpRef,
            )
            updatedHosts += 1
            if (existing.authType != profile.authType) secretDeletions += existing.id
        } else {
            hostInserts += PlannedHost(
                exportedId = exported.id,
                entity = profile.toEntity().copy(id = 0, rememberCredential = false, jumpHostId = null),
                jumpExportedRef = jumpRef,
            )
            addedHosts += 1
        }
    }

    // 快捷指令：校验、去重、按 (title, command) 匹配。
    val snippetInserts = mutableListOf<CommandSnippetEntity>()
    val snippetUpdates = mutableListOf<CommandSnippetEntity>()
    val seenSnippetKeys = mutableSetOf<String>()
    var addedSnippets = 0
    var updatedSnippets = 0
    var skippedSnippets = 0
    parsed.snippets.forEach { exported ->
        val model = exported.toModel()
        val error = model.validationError()
        if (error != null) {
            skippedSnippets += 1
            reasons += "跳过快捷指令 ${model.title.ifBlank { "（无标题）" }}：$error"
            return@forEach
        }
        val key = snippetKey(model.title, model.command)
        if (!seenSnippetKeys.add(key)) {
            skippedSnippets += 1
            reasons += "跳过重复快捷指令 ${model.title}"
            return@forEach
        }
        val existing = existingSnippetByKey[key]
        if (existing == null) {
            snippetInserts += model.toEntity()
            addedSnippets += 1
        } else {
            snippetUpdates += model.copy(id = existing.id).toEntity(existing)
            updatedSnippets += 1
        }
    }

    val report = ImportReport(
        addedHosts, updatedHosts, skippedHosts,
        addedSnippets, updatedSnippets, skippedSnippets,
        reasons,
    )
    return ImportPlan(hostInserts, hostUpdates, secretDeletions, snippetInserts, snippetUpdates, report)
}

private inline fun JSONArray.forEachJson(block: (JSONObject) -> Unit) {
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        block(item)
    }
}

private fun JSONObject.nullableLong(key: String): Long? =
    if (isNull(key)) null else optLong(key, 0L).takeIf { it != 0L }
