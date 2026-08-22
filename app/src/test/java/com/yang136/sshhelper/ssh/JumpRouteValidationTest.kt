package com.yang136.sshhelper.ssh

import com.yang136.sshhelper.data.AuthType
import com.yang136.sshhelper.data.HostProfile
import com.yang136.sshhelper.data.validateJumpRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JumpRouteValidationTest {
    private fun host(id: Long, name: String, jumpHostId: Long? = null) = HostProfile(
        id = id,
        name = name,
        hostname = "10.0.0.$id",
        port = 22,
        username = "user",
        authType = AuthType.PASSWORD,
        jumpHostId = jumpHostId,
    )

    private val hosts = listOf(host(1, "直连机"), host(2, "跳板机"), host(3, "目标机"))

    @Test
    fun directConnectionIsAlwaysValid() {
        assertNull(validateJumpRoute(hosts[0], hosts))
        assertNull(validateJumpRoute(hosts[0], emptyList()))
    }

    @Test
    fun singleLayerRouteIsValid() {
        val target = host(4, "新目标机", jumpHostId = 2)
        assertNull(validateJumpRoute(target, hosts + target))
    }

    @Test
    fun selfReferenceIsRejected() {
        val self = host(5, "自己", jumpHostId = 5)
        assertEquals("不能选择自己作为跳板机", validateJumpRoute(self, hosts + self))
    }

    @Test
    fun missingJumpTargetIsRejected() {
        val dangling = host(6, "悬空", jumpHostId = 99)
        assertEquals("跳板机不存在，请重新选择", validateJumpRoute(dangling, hosts + dangling))
    }

    @Test
    fun nestedJumpIsRejected() {
        // Target jumps via host 2, but host 2 itself already jumps via host 1 → two layers.
        val hosts = listOf(host(1, "直连机"), host(2, "跳板机", jumpHostId = 1), host(3, "目标机", jumpHostId = 2))
        assertEquals("仅支持一层跳板：跳板机本身不能再配置跳板", validateJumpRoute(hosts[2], hosts))
    }

    @Test
    fun indirectCycleThroughJumpIsRejectedAsNested() {
        // A jumps via B, and B jumps via A: B gains a jump, which the single-layer rule rejects.
        val a = host(7, "A", jumpHostId = 8)
        val b = host(8, "B", jumpHostId = 7)
        assertEquals("仅支持一层跳板：跳板机本身不能再配置跳板", validateJumpRoute(b, listOf(a, b)))
    }

    @Test
    fun hostAlreadyUsedAsJumpCannotGainItsOwnJump() {
        // Host 2 is used as the jump of host 3; editing host 2 to jump via host 1 must fail.
        val targets = hosts + host(9, "另一目标", jumpHostId = 2)
        val editedJump = host(2, "跳板机", jumpHostId = 1)
        assertEquals("该主机正被其他主机用作跳板机，不能再配置跳板", validateJumpRoute(editedJump, targets))
    }
}
