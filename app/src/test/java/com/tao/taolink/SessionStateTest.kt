package com.tao.tao.link

import com.tao.tao.link.protocol.SessionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {

    @Test
    fun idle_toDiscovered_allowed() {
        assertTrue(SessionState.IDLE.canTransitionTo(SessionState.DISCOVERED))
    }

    @Test
    fun idle_directToPairing_blocked() {
        assertFalse(SessionState.IDLE.canTransitionTo(SessionState.PAIRING))
    }

    @Test
    fun pairing_toConnected_allowed_toSelfHealing_blocked() {
        assertTrue(SessionState.PAIRING.canTransitionTo(SessionState.CONNECTED))
        assertFalse(SessionState.PAIRING.canTransitionTo(SessionState.SELF_HEALING))
    }

    @Test
    fun connected_toSelfHealing_allowed_selfHealingBackToConnected_allowed() {
        assertTrue(SessionState.CONNECTED.canTransitionTo(SessionState.SELF_HEALING))
        assertTrue(SessionState.SELF_HEALING.canTransitionTo(SessionState.CONNECTED))
    }

    @Test
    fun disconnected_canEnterSelfHealingOrIdle_notDirectPairing() {
        assertTrue(SessionState.DISCONNECTED.canTransitionTo(SessionState.SELF_HEALING))
        assertTrue(SessionState.DISCONNECTED.canTransitionTo(SessionState.IDLE))
        assertFalse(SessionState.DISCONNECTED.canTransitionTo(SessionState.PAIRING))
    }
}
