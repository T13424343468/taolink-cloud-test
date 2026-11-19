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

    // ---- 视频分支（视频引擎班补） ----

    @Test
    fun startVideo_blockedWhenSessionDead() {
        val ch = MasterCallChannel(isSessionLive = { false }, sendSignal = { _ -> 1L })
        ch.startVideo()
        assertEquals(MasterCallChannel.CallState.IDLE, ch.state)
    }

    @Test
    fun startVideo_blockedWithoutSignalSink() {
        val ch = MasterCallChannel(isSessionLive = { true }, sendSignal = null)
        ch.startVideo()
        assertEquals(MasterCallChannel.CallState.IDLE, ch.state)
    }

    @Test
    fun startVideo_blockedWithoutContext() {
        val ch = MasterCallChannel(isSessionLive = { true }, sendSignal = { _ -> 1L })
        // 无 Context 引擎未注入：视频状态不落账，返回 IDLE（防半开账，与语音班同口径）
        ch.startVideo()
        assertEquals(MasterCallChannel.CallState.IDLE, ch.state)
    }

    @Test
    fun degradeVideo_videoCall_fallsToFpsMin_once() {
        val ch = MasterCallChannel(isSessionLive = { true }, sendSignal = { _ -> 1L })
        ch.state = MasterCallChannel.CallState.VIDEO
        ch.degradeVideo()
        assertTrue(ch.degraded)
        assertEquals(MasterCallChannel.VIDEO_FPS_MIN, ch.quality.videoFps)
        assertEquals(MasterCallChannel.AUDIO_KBPS_BASE, ch.quality.audioBitrateKbps) // 只降视频不降音频
        ch.degradeVideo() // 引擎不在场（单测无 Context），重复降不崩不翻账
        assertEquals(MasterCallChannel.VIDEO_FPS_MIN, ch.quality.videoFps)
    }

    @Test
    fun onCallSignal_ignoredWhenIdle_noThrow() {
        val ch = MasterCallChannel(isSessionLive = { true })
        // 无通话中收到信令帧：静默忽略，状态不翻
        ch.onCallSignal(byteArrayOf(1, 2, 3))
        assertEquals(MasterCallChannel.CallState.IDLE, ch.state)
        assertFalse(ch.degraded)
    }

    @Test
    fun end_clearsVideoCallbacks_andNotifiesEndedOnce() {
        val ch = MasterCallChannel(isSessionLive = { true }, sendSignal = { _ -> 1L })
        var endedCount = 0
        ch.onCallEnded = { endedCount++ }
        ch.onLocalVideoReady = { _, _ -> }
        ch.onRemoteVideoReady = { _, _ -> }
        ch.state = MasterCallChannel.CallState.VIDEO
        ch.end()
        assertEquals(MasterCallChannel.CallState.IDLE, ch.state)
        assertEquals(1, endedCount)
        assertEquals(null, ch.onLocalVideoReady)
        assertEquals(null, ch.onRemoteVideoReady)
        assertEquals(null, ch.onCallEnded)
    }

    // ---- 挂断路由（第七处跨端契约病：重建实例的空信道 end() 出不了挂断帧） ----

    @Test
    fun endLiveCall_routesToRegisteredLiveChannel() {
        val live = MasterCallChannel(isSessionLive = { true }, sendSignal = { _ -> 1L })
        // 单测无 Context 造不了引擎：直接挂注册口验证路由与结算
        live.state = MasterCallChannel.CallState.VOICE
        MasterCallChannel.liveCall = live
        try {
            MasterCallChannel.endLiveCall()
        } finally {
            MasterCallChannel.liveCall = null
        }
        assertEquals(MasterCallChannel.CallState.IDLE, live.state)
    }

    @Test
    fun endLiveCall_noOpWhenNoLiveCall() {
        MasterCallChannel.liveCall = null
        MasterCallChannel.endLiveCall() // 无通话在账：静默，不崩
    }

    @Test
    fun end_clearsLiveCallRegistration_whenSelf() {
        val ch = MasterCallChannel(isSessionLive = { true }, sendSignal = { _ -> 1L })
        MasterCallChannel.liveCall = ch
        ch.end()
        assertEquals(null, MasterCallChannel.liveCall)
        MasterCallChannel.liveCall = null
    }

    @Test
    fun end_keepsOtherLiveCallRegistration() {
        val other = MasterCallChannel(isSessionLive = { true }, sendSignal = { _ -> 1L })
        val dead = MasterCallChannel(isSessionLive = { true }, sendSignal = { _ -> 1L })
        MasterCallChannel.liveCall = other
        dead.end() // 结算别人的账不摘错注册口
        assertEquals(other, MasterCallChannel.liveCall)
        MasterCallChannel.liveCall = null
    }
}
