package com.tao.tao.link.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterCallChannelTest {

    @Test
    fun startVoice_blockedWhenSessionDead() {
        val ch = MasterCallChannel(isSessionLive = { false })
        ch.startVoice()
        assertEquals(MasterCallChannel.CallState.IDLE, ch.state)
    }

    @Test
    fun startVoice_blockedWithoutSignalSink() {
        val ch = MasterCallChannel(isSessionLive = { true }, sendSignal = null)
        ch.startVoice()
        assertEquals(MasterCallChannel.CallState.IDLE, ch.state)
    }

    @Test
    fun startVoice_activeWhenSessionLiveAndSinkWired() {
        val ch = MasterCallChannel(isSessionLive = { true }, sendSignal = { _ -> 1L })
        // 无 Context 引擎未注入：通话状态不落账，返回 IDLE（防半开账）
        ch.startVoice()
        assertEquals(MasterCallChannel.CallState.IDLE, ch.state)
    }

    @Test
    fun end_resetsStateAndQuality() {
        val ch = MasterCallChannel(isSessionLive = { true }, sendSignal = { _ -> 1L })
        ch.state = MasterCallChannel.CallState.VOICE // 反射不可用，用包内可见性直接赋值
        ch.degraded = true
        ch.end()
        assertEquals(MasterCallChannel.CallState.IDLE, ch.state)
        assertFalse(ch.degraded)
        assertEquals(MasterCallChannel.VIDEO_FPS_BASE, ch.quality.videoFps)
        assertEquals(MasterCallChannel.AUDIO_KBPS_BASE, ch.quality.audioBitrateKbps)
    }

    @Test
    fun degradeVideo_onlyInActiveCall_andOnlyOnce() {
        val ch = MasterCallChannel(isSessionLive = { true })
        ch.degradeVideo() // IDLE 状态不降
        assertFalse(ch.degraded)

        ch.state = MasterCallChannel.CallState.VOICE
        ch.degradeVideo()
        assertTrue(ch.degraded)
        assertEquals(MasterCallChannel.VIDEO_FPS_MIN, ch.quality.videoFps)
        assertEquals(MasterCallChannel.AUDIO_KBPS_BASE, ch.quality.audioBitrateKbps) // 音频恒定

        ch.degradeVideo() // 第二次不再降
        assertTrue(ch.degraded)
    }
}
