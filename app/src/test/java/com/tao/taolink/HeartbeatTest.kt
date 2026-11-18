package com.tao.tao.link

import com.tao.tao.link.protocol.HeartbeatMonitor
import com.tao.tao.link.protocol.SelfHealingBackoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatMonitorTest {

    private class FakeClock(var now: Long = 1000L)

    @Test
    fun freshMonitor_notTimedOut() {
        val clock = FakeClock()
        val monitor = HeartbeatMonitor(clock::now)
        assertFalse(monitor.isTimedOut())
        assertFalse(monitor.isSelfHealing)
    }

    @Test
    fun beyondTimeoutLine_isTimedOut() {
        val clock = FakeClock()
        val monitor = HeartbeatMonitor(clock::now)
        clock.now += 15_000
        assertTrue(monitor.isTimedOut())
    }

    @Test
    fun withinTimeoutLine_notTimedOut() {
        val clock = FakeClock()
        val monitor = HeartbeatMonitor(clock::now)
        clock.now += 14_999
        assertFalse(monitor.isTimedOut())
    }

    @Test
    fun onPeerHeartbeat_resetsTimeoutAndExitsSelfHealing() {
        val clock = FakeClock()
        val monitor = HeartbeatMonitor(clock::now)
        clock.now += 20_000
        monitor.enterSelfHealing()
        monitor.onPeerHeartbeat()
        assertFalse(monitor.isTimedOut())
        assertFalse(monitor.isSelfHealing)
    }

    @Test
    fun enterSelfHealing_idempotent() {
        val clock = FakeClock()
        val monitor = HeartbeatMonitor(clock::now)
        monitor.enterSelfHealing()
        monitor.enterSelfHealing()
        assertTrue(monitor.isSelfHealing)
    }
}

class SelfHealingBackoffTest {

    @Test
    fun backoffSequence_doublesFromOneSecond_capsAtThirtySeconds() {
        val backoff = SelfHealingBackoff()
        val delays = mutableListOf<Long>()
        repeat(8) { delays.add(backoff.nextDelayMs()) }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 30000L), delays)
    }

    @Test
    fun reset_returnsToStartBackoff() {
        val backoff = SelfHealingBackoff()
        repeat(4) { backoff.nextDelayMs() }
        backoff.reset()
        assertEquals(1000L, backoff.nextDelayMs())
    }
}
