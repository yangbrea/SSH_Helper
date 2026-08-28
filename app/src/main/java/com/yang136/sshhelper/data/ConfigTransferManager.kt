package com.yang136.sshhelper.data

import androidx.room.withTransaction

/**
 * 配置导出/导入入口。导出不包含凭据；导入为增量语义（只增改、不删除），
 * 通过 [planIncrementalImport] 规划后在单事务内落库。
 */
class ConfigTransferManager(private val database: AppDatabase) {

    suspend fun export(): ConfigExportResult {
        val hosts = database.hostDao().getAll()
        val snippets = database.commandSnippetDao().getAll()
        return ConfigExportResult(
            json = buildExportJson(hosts, snippets),
            hostCount = hosts.size,
            snippetCount = snippets.size,
        )
    }

    /**
     * 只解析并规划 [text]，返回增量导入预览（不写库），供导入前确认。
     */
    suspend fun previewImport(text: String): ImportReport {
        if (text.length > CONFIG_IMPORT_MAX_CHARS) {
            throw IllegalArgumentException("文件过大（超过 ${CONFIG_IMPORT_MAX_CHARS / 1_000}KB），请确认是 SSH Helper 的导出文件")
        }
        val parsed = parseExportJson(text)
        return planIncrementalImport(
            parsed,
            database.hostDao().getAll(),
            database.commandSnippetDao().getAll(),
        ).report
    }

    /**
     * 增量导入 [text]。失败（格式/版本错误、事务异常）时抛出异常，不写入任何数据。
     */
    suspend fun importIncremental(text: String): ImportReport {
        if (text.length > CONFIG_IMPORT_MAX_CHARS) {
            throw IllegalArgumentException("文件过大（超过 ${CONFIG_IMPORT_MAX_CHARS / 1_000}KB），请确认是 SSH Helper 的导出文件")
        }
        val parsed = parseExportJson(text)
        val plan = planIncrementalImport(
            parsed,
            database.hostDao().getAll(),
            database.commandSnippetDao().getAll(),
        )
        // 导出文件中的主机 id -> 落库后的本地 id（新增 = 新行 id，更新 = 既有 id）。
        val resolvedIdByExported = mutableMapOf<Long, Long>()
        database.withTransaction {
            val hostDao = database.hostDao()
            plan.hostInserts.forEach { planned ->
                resolvedIdByExported[planned.exportedId] = hostDao.insert(planned.entity)
            }
            plan.hostUpdates.forEach { planned ->
                resolvedIdByExported[planned.exportedId] = planned.entity.id
                hostDao.update(
                    planned.entity.copy(jumpHostId = planned.jumpExportedRef?.let(resolvedIdByExported::get)),
                )
                if (planned.entity.id in plan.secretDeletions) {
                    // 导入改动了认证方式：旧凭据不再适用，清除该主机的已存凭据。
                    database.secretDao().deleteForHost(planned.entity.id)
                }
            }
            // 新增主机若引用另一个新增主机作跳板，此时才拿到目标 id，补写 jumpHostId。
            plan.hostInserts.forEach { planned ->
                planned.jumpExportedRef?.let { ref ->
                    resolvedIdByExported[ref]?.let { targetId ->
                        hostDao.update(planned.entity.copy(jumpHostId = targetId))
                    }
                }
            }
            val snippetDao = database.commandSnippetDao()
            plan.snippetInserts.forEach { snippet ->
                snippetDao.insert(snippet.copy(hostId = snippet.hostId?.let(resolvedIdByExported::get)))
            }
            plan.snippetUpdates.forEach { snippet ->
                snippetDao.update(snippet.copy(hostId = snippet.hostId?.let(resolvedIdByExported::get)))
            }
        }
        return plan.report
    }
}
