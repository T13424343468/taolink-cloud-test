package com.tao.tao.link.call

import com.tao.tao.link.LinkLog

/**
 * 主控端通话（契约 #10 CallChannel 主控侧实现，接口对齐班交付）。
 * WebRTC 集成待下期（老板拍板分岔 2 = A，技术路线-决策清单）；
 * 本班落接口位：连接态守门、通话状态记账、弱网降档只降视频不降音频（协议文档 §2-10）。
 * 搁置条件与负责人：媒体引擎接入由下期 WebRTC 集成班承接，接口零改动。
 */
class MasterCallChannel(private val isSessionLive: () -> Boolean) : CallChannel {

    enum class CallState { IDLE, VOICE, VIDEO }

    var state: CallState = CallState.IDLE
        private set

    var quality: CallChannel.Quality = CallChannel.Quality(VIDEO_FPS_BASE, AUDIO_KBPS_BASE)
        private set

    var degraded: Boolean = false
        private set

    override fun startVoice() {
        if (!guard("startVoice")) return
        state = CallState.VOICE
        LinkLog.d(TAG, "语音对讲接口位已记（WebRTC 集成待下期）")
    }

    override fun startVideo() {
        if (!guard("startVideo")) return
        state = CallState.VIDEO
        LinkLog.d(TAG, "视频通话接口位已记（WebRTC 集成待下期）")
    }

    override fun end() {
        state = CallState.IDLE
        degraded = false
        quality = CallChannel.Quality(VIDEO_FPS_BASE, AUDIO_KBPS_BASE)
    }

    override fun degradeVideo() {
        if (state == CallState.IDLE) return
        if (degraded) return
        degraded = true
        quality = CallChannel.Quality(VIDEO_FPS_MIN, quality.audioBitrateKbps) // 音频恒定（稳定优先）
        LinkLog.d(TAG, "弱网降档：视频 ${quality.videoFps}fps，音频保持 ${quality.audioBitrateKbps}kbps")
    }

    private fun guard(action: String): Boolean {
        if (!isSessionLive()) {
            LinkLog.w(TAG, "$action 拒绝：会话未连接")
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "MasterCall"
        const val VIDEO_FPS_BASE = 24
        const val VIDEO_FPS_MIN = 8
        const val AUDIO_KBPS_BASE = 32
    }
}
