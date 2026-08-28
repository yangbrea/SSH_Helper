package com.yang136.sshhelper.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfigTransferTest {

    private fun hostEntity(
        id: Long = 1,
        name: String = "web",
        hostname: String = "10.0.0.1",
        port: Int = 22,
        username: String = "root",
        authType: AuthType = AuthType.PASSWORD,
        rememberCredential: Boolean = false,
        jumpHostId: Long? = null,
    ) = HostEntity(
        id = id, name = name, hostname = hostname, port = port, username = username,
        authType = authType, rememberCredential = rememberCredential, jumpHostId = jumpHostId,
    )

    private fun snippetEntity(
        id: Long = 1,
        title: String = "ps",
        command: String = "ps aux",
        groupName: String = "常用",
        hostId: Long? = null,
    ) = CommandSnippetEntity(
        id = id, title = title, command = command, groupName = groupName, hostId = hostId,
    )

    private fun exportHost(
        id: Long,
        name: String = "web",
        hostname: String = "10.0.0.1",
        port: Int = 22,
        username: String = "root",
        authType: AuthType = AuthType.PASSWORD,
        rememberCredential: Boolean = false,
        privateKeyName: String? = null,
        autoReconnect: Boolean = false,
        jumpHostId: Long? = null,
    ) = ExportHost(
        id = id, name = name, hostname = hostname, port = port, username = username,
        authType = authType, rememberCredential = rememberCredential, privateKeyName = privateKeyName,
        autoReconnect = autoReconnect, jumpHostId = jumpHostId,
        proxyType = null, proxyHost = null, proxyPort = null, proxyUsername = null,
    )

    private fun exportSnippet(
        id: Long,
        title: String = "ps",
        command: String = "ps aux",
        groupName: String = "常用",
        hostId: Long? = null,
    ) = ExportSnippet(
        id = id, title = title, command = command, groupName = groupName,
        hostId = hostId, executeImmediately = false, sortOrder = 0,
    )

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("期望抛出 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun exportJson_roundTripsThroughParse() {
        val json = buildExportJson(
            hosts = listOf(hostEntity(1, jumpHostId = 2), hostEntity(2, name = "跳板", hostname = "10.0.0.9", username = "admin")),
            snippets = listOf(snippetEntity(1, hostId = 2), snippetEntity(2, title = "df", command = "df -h", groupName = "运维")),
        )
        val root = JSONObject(json)
        assertEquals("ssh-helper", root.optString("app"))
        assertEquals("config-export", root.optString("kind"))
        assertEquals(1, root.optInt("version"))

        val parsed = parseExportJson(json)
        assertEquals(2, parsed.hosts.size)
        val first = parsed.hosts.first { it.id == 1L }
        assertEquals("web", first.name)
        assertEquals("10.0.0.1", first.hostname)
        assertEquals(22, first.port)
        assertEquals("root", first.username)
        assertEquals(AuthType.PASSWORD, first.authType)
        assertEquals(2L, first.jumpHostId)
        assertEquals(2, parsed.snippets.size)
        val bound = parsed.snippets.first { it.id == 1L }
        assertEquals("ps", bound.title)
        assertEquals(2L, bound.hostId)
        val unbound = parsed.snippets.first { it.id == 2L }
        assertEquals("df -h", unbound.command)
        assertNull(unbound.hostId)
    }

    @Test
    fun envelopeValidation_rejectsForeignOrUnsupportedFiles() {
        assertThrows { parseExportJson("这不是 JSON") }
        assertThrows { parseExportJson("""{"app":"other-app","kind":"config-export","version":1}""") }
        assertThrows { parseExportJson("""{"app":"ssh-helper","kind":"full-backup","version":1}""") }
        assertThrows { parseExportJson("""{"app":"ssh-helper","kind":"config-export","version":99}""") }
    }

    @Test
    fun emptyDatabase_importsEverythingAsNewWithoutCredentials() {
        val parsed = ParsedExport(
            hosts = listOf(exportHost(1, rememberCredential = true)),
            snippets = listOf(exportSnippet(1, hostId = 1)),
        )
        val plan = planIncrementalImport(parsed, existingHosts = emptyList(), existingSnippets = emptyList())

        assertEquals(1, plan.hostInserts.size)
        val inserted = plan.hostInserts.single().entity
        assertEquals(0L, inserted.id)
        assertFalse("新增主机必须不携带凭据标记", inserted.rememberCredential)
        assertEquals("10.0.0.1", inserted.hostname)
        assertTrue(plan.hostUpdates.isEmpty())
        assertTrue(plan.secretDeletions.isEmpty())

        assertEquals(1, plan.snippetInserts.size)
        assertEquals(0L, plan.snippetInserts.single().id)
        assertEquals(1L, plan.snippetInserts.single().hostId)
        assertEquals(ImportReport(1, 0, 0, 1, 0, 0, emptyList()), plan.report)
    }

    @Test
    fun matchingHosts_areUpdatedInPlaceWithoutDuplicates() {
        val existing = hostEntity(7, name = "旧名字", rememberCredential = true)
        val parsed = ParsedExport(hosts = listOf(exportHost(1, name = "新名字")), snippets = emptyList())

        val plan = planIncrementalImport(parsed, existingHosts = listOf(existing), existingSnippets = emptyList())

        assertTrue(plan.hostInserts.isEmpty())
        assertEquals(1, plan.hostUpdates.size)
        val updated = plan.hostUpdates.single().entity
        assertEquals("必须保留既有本地 id", 7L, updated.id)
        assertEquals("新名字", updated.name)
        assertTrue("必须保留既有 rememberCredential 与已存凭据", updated.rememberCredential)
        assertTrue(plan.secretDeletions.isEmpty())
        assertEquals(ImportReport(0, 1, 0, 0, 0, 0, emptyList()), plan.report)
    }

    @Test
    fun reimportingSameFile_isIdempotent() {
        val parsed = ParsedExport(hosts = listOf(exportHost(1)), snippets = emptyList())
        val first = planIncrementalImport(parsed, emptyList(), emptyList())
        assertEquals(1, first.hostInserts.size)

        // 模拟第一次落库后的既有数据（同自然键）。
        val afterFirst = listOf(first.hostInserts.single().entity)
        val second = planIncrementalImport(parsed, afterFirst, emptyList())

        assertTrue(second.hostInserts.isEmpty())
        assertEquals(1, second.hostUpdates.size)
        assertEquals(0, second.report.addedHosts)
        assertEquals(1, second.report.updatedHosts)
    }

    @Test
    fun jumpAndSnippetReferences_areRemappedAfterInsert() {
        val parsed = ParsedExport(
            hosts = listOf(
                exportHost(10, name = "目标机", hostname = "10.0.0.2", jumpHostId = 20),
                exportHost(20, name = "跳板机", hostname = "10.0.0.9", username = "admin"),
            ),
            snippets = listOf(exportSnippet(1, hostId = 10)),
        )
        val plan = planIncrementalImport(parsed, emptyList(), emptyList())

        val target = plan.hostInserts.first { it.exportedId == 10L }
        assertEquals("跳板引用必须保留导出 id 供落库时解析", 20L, target.jumpExportedRef)
        val jumpHost = plan.hostInserts.first { it.exportedId == 20L }
        assertNull(jumpHost.jumpExportedRef)
        assertEquals(1, plan.snippetInserts.size)
        assertEquals(10L, plan.snippetInserts.single().hostId)
    }

    @Test
    fun jumpToExistingLocalHost_keepsExportedReference() {
        val existing = hostEntity(7, hostname = "10.0.0.9", username = "admin")
        val parsed = ParsedExport(
            hosts = listOf(exportHost(10, hostname = "10.0.0.2", jumpHostId = 20), exportHost(20, hostname = "10.0.0.9", username = "admin")),
            snippets = emptyList(),
        )
        val plan = planIncrementalImport(parsed, listOf(existing), emptyList())

        val target = plan.hostInserts.single { it.exportedId == 10L }
        assertEquals(20L, target.jumpExportedRef)
        val jumpHost = plan.hostUpdates.single { it.exportedId == 20L }
        assertEquals("跳板机必须更新到既有本地主机", 7L, jumpHost.entity.id)
    }

    @Test
    fun invalidAndDuplicateEntries_areSkippedWithCounts() {
        val parsed = ParsedExport(
            hosts = listOf(
                exportHost(1, name = ""),
                exportHost(2),
                exportHost(3, name = "重复"),
            ),
            snippets = listOf(
                exportSnippet(1, title = ""),
                exportSnippet(2, title = "dup"),
                exportSnippet(3, title = "dup"),
            ),
        )
        val plan = planIncrementalImport(parsed, emptyList(), emptyList())

        assertEquals(1, plan.hostInserts.size)
        assertEquals(1, plan.snippetInserts.size)
        assertEquals(ImportReport(1, 0, 2, 1, 0, 2, plan.report.skippedReasons), plan.report)
        assertEquals(4, plan.report.skippedReasons.size)
    }

    @Test
    fun authTypeChangeOnMatchedHost_marksSecretForDeletion() {
        val existing = hostEntity(7, authType = AuthType.PASSWORD, rememberCredential = true)
        val parsed = ParsedExport(
            hosts = listOf(
                exportHost(1, authType = AuthType.PRIVATE_KEY, privateKeyName = "key.pem"),
            ),
            snippets = emptyList(),
        )
        val plan = planIncrementalImport(parsed, listOf(existing), emptyList())

        assertEquals(setOf(7L), plan.secretDeletions)
        assertEquals(AuthType.PRIVATE_KEY, plan.hostUpdates.single().entity.authType)
    }

    @Test
    fun snippets_areUpdatedByTitleAndCommand() {
        val existing = snippetEntity(5, hostId = 3)
        val parsed = ParsedExport(
            hosts = emptyList(),
            snippets = listOf(
                ExportSnippet(
                    id = 1, title = "ps", command = "ps aux", groupName = "运维",
                    hostId = 2, executeImmediately = true, sortOrder = 4,
                ),
            ),
        )
        val plan = planIncrementalImport(parsed, emptyList(), listOf(existing))

        assertTrue(plan.snippetInserts.isEmpty())
        val updated = plan.snippetUpdates.single()
        assertEquals("必须保留既有快捷指令 id", 5L, updated.id)
        assertEquals("运维", updated.groupName)
        assertEquals(4, updated.sortOrder)
        assertTrue(updated.executeImmediately)
        assertEquals(2L, updated.hostId)
    }
}
