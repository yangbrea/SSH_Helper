package com.yang136.sshhelper.ui

import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.ConflictPolicy
import com.yang136.sshhelper.data.ForwardType
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.TransferDirection
import com.yang136.sshhelper.data.TransferStatus
import com.yang136.sshhelper.sftp.TransferJob
import com.yang136.sshhelper.ssh.ConnectionState
import com.yang136.sshhelper.ssh.ForwardState
import com.yang136.sshhelper.ssh.ManagedSessionState
import com.yang136.sshhelper.ssh.PortForwardRule
import com.yang136.sshhelper.ssh.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUiModelsTest {
    private val host = HostProfile(7, "生产机", "example.test", 22, "root", AuthType.PASSWORD, false)

    @Test
    fun topLevelDestination_hasStableFallback() {
        assertEquals(AppDestination.ACTIVITY, AppDestination.fromId("activity"))
        assertEquals(AppDestination.HOSTS, AppDestination.fromId("unknown"))
    }

    @Test
    fun hostWorkspace_filtersAndSortsSessions() {
        val idle = session("B", ConnectionState.Idle)
        val connected = session("A", ConnectionState.Connected("ready"))
        val other = idle.copy(id = SessionId("other"), profile = host.copy(id = 8))
        val state = buildHostWorkspaceUiState(host, listOf(idle, other, connected), emptyList(), emptyList(), emptyMap(), setOf(7))
        assertEquals(listOf("A", "B"), state.sessions.map { it.displayName })
        assertTrue(state.documentAuthorized)
    }

    @Test
    fun activity_putsExceptionsFirstAndLimitsRecentTransfers() {
        val transfers = (1L..8L).map { id ->
            transfer(id, if (id == 8L) TransferStatus.FAILED else TransferStatus.COMPLETED, updatedAt = id)
        }
        val rule = PortForwardRule(3, host.id, "API", ForwardType.LOCAL, "127.0.0.1", 8080, "127.0.0.1", 80)
        val state = buildActivityUiState(
            sessions = listOf(session("locked", ConnectionState.Idle).copy(needsVaultUnlock = true)),
            transfers = transfers,
            rules = listOf(rule),
            forwardStates = mapOf(3L to ForwardState.Failed("closed")),
            failedWritebacks = 2,
        )
        assertEquals(ActivityAttentionKind.WRITEBACK, state.attention.first().kind)
        assertEquals(5, state.recentTransfers.size)
        assertEquals(8L, state.recentTransfers.first().id)
        assertEquals(1, state.activeForwards.size)
    }

    private fun session(name: String, connection: ConnectionState) = ManagedSessionState(SessionId(name), host, name, connection)

    private fun transfer(id: Long, status: TransferStatus, updatedAt: Long) = TransferJob(
        id, host.id, TransferDirection.DOWNLOAD, "/a", "/b", 10, 10, status,
        ConflictPolicy.ASK, null, 0, updatedAt,
    )
}
