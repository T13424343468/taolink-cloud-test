package com.tao.tao.link.call

import android.content.Context
import com.tao.tao.link.LinkLog
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * 被控端通话宿主（语音引擎班留注入口，本班接线）：
 * 引擎生命周期挂在驻留服务上——对端 SDP offer 到达即建引擎应答（被控端零操作，主控端发起通话）；
 * 挂断即释放。CALL_SIGNAL 帧经服务桥进出本宿主，引擎信令回程走既有加密会话。
 * 通话画面经事件口交 UI（轨 + 渲染上下文成对交付，UI 无需回查引擎）；
 * 无 UI 在场时通话照常（声音先通，画面等 UI 挂上再渲染）。
 */
class ControlledCallHost(
    private val context: Context,
    private val sendSignal: (ByteArray) -> Unit,
    private val onIncoming: () -> Unit = {},
) {

    /** 通话事件口：UI 在场时经 [Companion.ui] 订阅，退场即清（生命周期口，不持界面引用）。 */
    interface Listener {
        fun onIncomingCall()
        fun onLocalVideoReady(track: VideoTrack, egl: EglBase.Context)
        fun onRemoteVideoReady(track: VideoTrack, egl: EglBase.Context)
        fun onCallEnded()
    }

    private var engine: WebRtcVideoCallEngine? = null

    /** 服务桥入口：CALL_SIGNAL 帧（AUDIO 路由）从这进引擎。 */
    fun onCallSignal(payload: ByteArray) {
        if (CallSignalCodec.isHangup(payload)) {
            LinkLog.d(TAG, "对端挂断，通话结束")
            end()
            return
        }
        if (engine == null && CallSignalCodec.parseSdp(payload)?.isOffer == true) {
            LinkLog.d(TAG, "对端来电，引擎应答")
            onIncoming() // 轻提示（通知点开才见画面）：被控零操作，通话不因无人看画面而断
            ui?.onIncomingCall()
            // 引擎回调里要交付渲染上下文，但本地值未初始化前不可自引：经空安全引用落地（回调在 start 后才触发）
            var eRef: WebRtcVideoCallEngine? = null
            val e = WebRtcVideoCallEngine(
                context = context,
                isInitiator = false,
                signalSink = { sendSignal(it) },
                onRemoteVideoTrack = { track ->
                    eRef?.let { snapshotRemote(track, it.eglBase.eglBaseContext) }
                    eRef?.let { ui?.onRemoteVideoReady(track, it.eglBase.eglBaseContext) }
                },
                onLocalVideoReady = { track ->
                    eRef?.let { snapshotLocal(track, it.eglBase.eglBaseContext) }
                    eRef?.let { ui?.onLocalVideoReady(track, it.eglBase.eglBaseContext) }
                },
                onHangup = { end() },
            )
            eRef = e
            engine = e
            e.start()
        }
        engine?.onCallSignal(payload)
    }

    fun end() {
        engine?.end()
        engine = null
        clearSnapshots()
        ui?.onCallEnded()
    }

    companion object {

        private const val TAG = "ControlledCall"

        /**
         * 通话事件总线：被控端无 Binder 绑定脚手架（大道至简），驻留服务单实例 →
         * 单槽 UI 监听足矣；活动退场清槽，服务发帧无人接收时事件静默丢弃（通话不因无画面而断）。
         * 挂上即补投通话在场期间的画面轨（UI 迟到不丢画面；轨在通话期内有效，挂断即清快照）。
         */
        @Volatile
        var ui: Listener? = null
            set(value) {
                field = value
                if (value != null) {
                    lastRemote?.let { (t, e) -> value.onRemoteVideoReady(t, e) }
                    lastLocal?.let { (t, e) -> value.onLocalVideoReady(t, e) }
                }
            }

        @Volatile
        private var lastLocal: Pair<VideoTrack, EglBase.Context>? = null

        @Volatile
        private var lastRemote: Pair<VideoTrack, EglBase.Context>? = null

        /** 宿主记画面快照（UI 迟到补投用）；挂断即清，轨不外泄。 */
        private fun snapshotLocal(track: VideoTrack, egl: EglBase.Context) {
            lastLocal = track to egl
        }

        private fun snapshotRemote(track: VideoTrack, egl: EglBase.Context) {
            lastRemote = track to egl
        }

        private fun clearSnapshots() {
            lastLocal = null
            lastRemote = null
        }
    }
}
