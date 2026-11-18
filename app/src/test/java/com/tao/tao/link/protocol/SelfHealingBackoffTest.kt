package com.tao.tao.link.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** 自愈退避考卷：1s 起步、30s 封顶、成功归零（协议文档 §3）。 */
class SelfHealingBackoffTest {

    @Test
    fun `delays double from start up to cap`() {
        val backoff = SelfHealingBackoff()
        assertEquals(1_000L, backoff.nextDelayMs())
        assertEquals(2_000L, backoff.nextDelayMs())
        assertEquals(4_000L, backoff.nextDelayMs())
        assertEquals(8_000L, backoff.nextDelayMs())
        assertEquals(16_000L, backoff.nextDelayMs())
        assertEquals(30_000L, backoff.nextDelayMs())
        assertEquals(30_000L, backoff.nextDelayMs())
    }

    @Test
    fun `reset returns to start`() {
        val backoff = SelfHealingBackoff()
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        backoff.reset()
        assertEquals(HeartbeatPolicy.BACKOFF_START_MS, backoff.nextDelayMs())
    }
}
