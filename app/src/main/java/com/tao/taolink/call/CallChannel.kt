package com.tao.tao.link.call

/**
 * 通话契约（协议文档 §2-10）：语音对讲 + 视频通话同通道；
 * 稳定优先——通话卡顿先降视频帧率、不降音频（协议文档 §2-10）。
 */
interface CallChannel {
    data class Quality(val videoFps: Int, val audioBitrateKbps: Int)

    fun startVoice()

    fun startVideo()

    fun end()

    /** 弱网降档：只降视频不降音频。 */
    fun degradeVideo()
}
