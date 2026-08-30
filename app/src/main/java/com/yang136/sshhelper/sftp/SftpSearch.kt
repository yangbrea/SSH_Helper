package com.yang136.sshhelper.sftp

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** 递归搜索命中的条目,附带所在目录与元数据,便于结果展示与预览。 */
data class SftpSearchHit(
    val path: String,
    val name: String,
    val parentDir: String,
    val type: RemoteFileType,
    val size: Long = 0,
    val modifiedAt: Long = 0,
    val permissions: Int = 0,
) {
    /** 还原为 [RemoteFile],供预览/媒体播放等复用现有流程(uid/gid 仅属性对话框使用,搜索不携带)。 */
    fun toRemoteFile() = RemoteFile(path, name, type, size, modifiedAt, permissions, uid = 0, gid = 0)
}

/** 递归搜索配置,防止超大目录失控。 */
data class SftpSearchConfig(
    /** 最多向下搜索的目录层数(root 为第 0 层)。 */
    val maxDepth: Int = 8,
    /** 命中结果数量上限,达到即停止。 */
    val maxResults: Int = 300,
    /** 是否匹配隐藏文件与隐藏目录(以 `.` 开头)。 */
    val includeHidden: Boolean = false,
)

/**
 * 在 [root] 下递归搜索名称包含 [query] 的文件/目录(大小写不敏感)。
 *
 * - 广度优先遍历 [SftpClient.list],结果按遍历顺序(天然按目录分组)
 * - 只沿普通目录下钻,**不跟随符号链接目录**(防止循环)
 * - 默认跳过隐藏文件与隐藏目录;单个目录读取失败时跳过该目录继续
 * - 达到 [SftpSearchConfig.maxResults] 提前返回;协程取消时立即中止
 */
suspend fun searchRemoteSftp(
    client: SftpClient,
    root: String,
    query: String,
    config: SftpSearchConfig = SftpSearchConfig(),
): List<SftpSearchHit> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val results = ArrayList<SftpSearchHit>(64)
    // BFS 队列:(目录路径, 层数);root 为第 0 层。
    // visited 防止服务器透明解析符号链接时出现目录环导致死循环。
    val visited = HashSet<String>()
    val queue = ArrayDeque<Pair<String, Int>>()
    queue.add(normalizeRemotePath(root) to 0)
    while (queue.isNotEmpty()) {
        coroutineContext.ensureActive()
        val (dir, depth) = queue.removeFirst()
        if (!visited.add(dir)) continue
        val entries = runCatching { client.list(dir) }.getOrDefault(emptyList())
        for (entry in entries) {
            coroutineContext.ensureActive()
            val hidden = entry.name.startsWith('.')
            if (hidden && !config.includeHidden) continue
            if (entry.name.contains(needle, ignoreCase = true)) {
                results += SftpSearchHit(entry.path, entry.name, dir, entry.type, entry.size, entry.modifiedAt, entry.permissions)
                if (results.size >= config.maxResults) return results
            }
            // 只下钻普通目录(符号链接目录不跟随),且不越过深度上限
            if (entry.type == RemoteFileType.DIRECTORY && depth < config.maxDepth) {
                queue.add(entry.path to depth + 1)
            }
        }
    }
    return results
}
