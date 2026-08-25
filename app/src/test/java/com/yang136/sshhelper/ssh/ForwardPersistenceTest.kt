package com.yang136.sshhelper.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 转发意图持久化格式（desired 规则 ID 集合 ↔ DataStore stringSet）的编解码测试。
 * 进程被系统回收后按此集合恢复转发，而非全局布尔值（避免手动规则丢失、
 * 已停止的 autoStart 规则复活）。
 */
class ForwardPersistenceTest {
    @Test
    fun desiredRuleIdsRoundTrip() {
        val ids = setOf(1L, 42L, 999L)
        assertEquals(ids, decodeDesiredRuleIds(encodeDesiredRuleIds(ids)))
    }

    @Test
    fun desiredRuleIdsHandleEmptyAndGarbage() {
        assertEquals(emptySet<Long>(), decodeDesiredRuleIds(emptySet()))
        assertEquals(emptySet<Long>(), encodeDesiredRuleIds(emptySet()))
        // 损坏/非法条目被丢弃，合法条目保留。
        assertEquals(setOf(7L), decodeDesiredRuleIds(setOf("7", "abc", "", "12.5")))
    }

    @Test
    fun desiredRuleIdsSurviveCleanupSemantics() {
        // 用户停止一条规则后从集合中移除；其余规则保留。
        val current = encodeDesiredRuleIds(setOf(1L, 2L, 3L))
        val afterStop = encodeDesiredRuleIds(decodeDesiredRuleIds(current) - 2L)
        assertEquals(setOf(1L, 3L), decodeDesiredRuleIds(afterStop))
    }
}
