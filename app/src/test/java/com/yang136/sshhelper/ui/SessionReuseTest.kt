package com.yang136.sshhelper.ui

import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.ssh.ConnectionState
import com.yang136.sshhelper.ssh.ManagedSessionState
import com.yang136.sshhelper.ssh.SessionFeature
import com.yang136.sshhelper.ssh.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 主机级会话复用规则：终端与文件系统共享同一 SSH 连接时的主会话选择。 */
class SessionReuseTest {

    private fun host(id: Long) = HostProfile(
        id = id, name = "host$id", hostname = "10.0.0.$id", port = 22, username = "root",
        authType = AuthType.PASSWORD,
    )

    private fun session(id: String, hostId: Long, vararg features: SessionFeature) = ManagedSessionState(
        id = SessionId(id),
        profile = host(hostId),
        displayName = id,
        connection = ConnectionState.Connected("t"),
        features = features.toSet(),
    )

    @Test
    fun emptyList_returnsNull() {
        assertNull(selectReusableSession(emptyList(), 1L))
    }

    @Test
    fun returnsPrimarySessionOfTheHost() {
        val list = listOf(session("a", 1), session("b", 1), session("c", 2))
        assertEquals("a", selectReusableSession(list, 1L)?.id?.value)
    }

    @Test
    fun ignoresSessionsOfOtherHosts() {
        val list = listOf(session("a", 2))
        assertNull(selectReusableSession(list, 1L))
    }

    @Test
    fun skipsPureForwardSessions_butPrefersReusableOne() {
        val list = listOf(
            session("fwd", 1, SessionFeature.PORT_FORWARD),
            session("term", 1, SessionFeature.SHELL),
        )
        assertEquals("term", selectReusableSession(list, 1L)?.id?.value)
    }

    @Test
    fun onlyForwardSession_isNotReusable() {
        val list = listOf(session("fwd", 1, SessionFeature.PORT_FORWARD))
        assertNull(selectReusableSession(list, 1L))
    }

    @Test
    fun shellSftpAndMixedSessions_areReusable() {
        assertEquals("a", selectReusableSession(listOf(session("a", 1, SessionFeature.SHELL)), 1L)?.id?.value)
        assertEquals("b", selectReusableSession(listOf(session("b", 1, SessionFeature.SFTP)), 1L)?.id?.value)
        assertEquals("c", selectReusableSession(listOf(session("c", 1, SessionFeature.SHELL, SessionFeature.SFTP)), 1L)?.id?.value)
    }
}
