package com.tao.tao.link.call

import com.tao.tao.link.LinkLog

/**
 * 主控端通话（契约 #10 CallChannel 主控侧实现）。
 * 语音与视频引擎均已接入（老板拍板分岔 2 = A，WebRTC）：语音对讲与视频通话同走真实媒体采集，
 * 信令经既有会话 CALL_SIGNAL 帧（AUDIO 路由）。连接态守门、通话状态记账、弱网降档只降视频不降音频（协议文档 §2-10）。
 * 通话画面绑定（本地小窗/远端大窗）由通话页从引擎取轨，本类只管状态与降档。
 */
class MasterCallChannel(
    private val isSessionLive: () -> Boolean,
    private val sendSignal: ((ByteArray) -> Long)? = null,
) : CallChannel {

    enum class CallState { IDLE, VOICE, VIDEO }

    var state: CallState = CallState.IDLE
        internal set

    var quality: CallChannel.Quality = CallChannel.Quality(VIDEO_FPS_BASE, AUDIO_KBPS_BASE)
        private set

    var degraded: Boolean = false
        internal set

    private var voiceEngine: WebRtcVoiceEngine? = null
    private var videoEngineRef: WebRtcVideoCallEngine? = null

    /** 结算重入闸：pc.close() 会同步回调 onHangup 重入 end()，二次 close 原生层要炸（云跑实弹 2026-11-19 实锤进程被杀）。 */
    private val settling = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 通话页绑定视频画面的轨回调（启动引擎前注入；轨与渲染上下文成对交付）。 */
    var onLocalVideoReady: ((org.webrtc.VideoTrack, org.webrtc.EglBase.Context) -> Unit)? = null
    var onRemoteVideoReady: ((org.webrtc.VideoTrack, org.webrtc.EglBase.Context) -> Unit)? = null

    /** 通话结束口（对端挂断/连接失败经引擎回到这里）：UI 收到即退场，不向引擎回查原因。 */
    var onCallEnded: (() -> Unit)? = null

    override fun startVoice() {
        if (!guard("startVoice")) return
        val sink = sendSignal
        if (sink == null) {
            LinkLog.w(TAG, "startVoice 拒绝：未接会话信令出口")
            return
        }
        val context = appContext
        if (context == null) {
            LinkLog.w(TAG, "startVoice 拒绝：未注入 Context")
            return
        }
        state = CallState.VOICE
        liveCall = this // 通话落账即挂注册口：挂断路由找活信道，不找重建页的空信道
        voiceEngine = WebRtcVoiceEngine(
            context = context,
            isInitiator = true,
            signalSink = { sink(it) },
            onRemoteAudioReady = { LinkLog.d(TAG, "远端音频已就绪") },
            onHangup = { end() },
        )
        voiceEngine?.start()
        LinkLog.d(TAG, "语音对讲已发起（WebRTC）")
    }

    /** 对端信令入口：由会话层 AUDIO 路由帧转发（语音/视频引擎同通道，谁的账在谁家）。 */
    fun onCallSignal(payload: ByteArray) {
        val voice = voiceEngine
        val video = videoEngineRef
        if (voice == null && video == null) {
            LinkLog.d(TAG, "无通话中，忽略信令帧")
            return
        }
        voice?.onCallSignal(payload)
        video?.onCallSignal(payload)
    }

    override fun startVideo() {
        if (!guard("startVideo")) return
        val sink = sendSignal
        if (sink == null) {
            LinkLog.w(TAG, "startVideo 拒绝：未接会话信令出口")
            return
        }
        val context = appContext
        if (context == null) {
            LinkLog.w(TAG, "startVideo 拒绝：未注入 Context")
            return
        }
        state = CallState.VIDEO
        liveCall = this // 同语音：通话落账即挂注册口
        // 引擎回调里要交付渲染上下文，但本地值未初始化前不可自引：经空安全引用落地（回调在 start 后才触发）
        var engineRef: WebRtcVideoCallEngine? = null
        val engine = WebRtcVideoCallEngine(
            context = context,
            isInitiator = true,
            signalSink = { sink(it) },
            onRemoteVideoTrack = { track ->
                LinkLog.d(TAG, "远端视频轨就绪")
                engineRef?.let { onRemoteVideoReady?.invoke(track, it.eglBase.eglBaseContext) }
            },
            onLocalVideoReady = { track ->
                LinkLog.d(TAG, "本地视频轨就绪")
                engineRef?.let { onLocalVideoReady?.invoke(track, it.eglBase.eglBaseContext) }
            },
            onHangup = { end() },
        )
        engineRef = engine
        videoEngineRef = engine
        engine.start()
        LinkLog.d(TAG, "视频通话已发起（WebRTC，摄像头采集+双向画面）")
    }

    override fun end() {
        if (!settling.compareAndSet(false, true)) return // 重入闸：结算中的 end() 直接落空
        try {
            val ended = onCallEnded
            if (liveCall === this) liveCall = null // 结算先摘注册口，防挂完后路由到死信道
            // 挂断必须出网告知对端：只拆自己会让对端引擎永不知情、占线假死（第六处跨端契约病）
            if (voiceEngine != null || videoEngineRef != null) {
                try { sendSignal?.invoke(CallSignalCodec.encodeHangup()) } catch (_: Exception) {}
            }
            voiceEngine?.end()
            voiceEngine = null
            videoEngineRef?.end()
            videoEngineRef = null
            onLocalVideoReady = null
            onRemoteVideoReady = null
            onCallEnded = null
            state = CallState.IDLE
            degraded = false
            quality = CallChannel.Quality(VIDEO_FPS_BASE, AUDIO_KBPS_BASE)
            ended?.invoke() // 结算完再通知 UI 退场（避免 UI 在半结算态抢先释放）
        } finally {
            settling.set(false) // 账已清，后续 end() 是无害空转
        }
    }

    override fun degradeVideo() {
        if (state == CallState.IDLE) return
        if (degraded) return
        degraded = true
        quality = CallChannel.Quality(VIDEO_FPS_MIN, quality.audioBitrateKbps) // 音频恒定（稳定优先）
        videoEngineRef?.degradeVideoTo(VIDEO_FPS_MIN)
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

        /**
         * 活着的通话信道（第七处跨端契约病：挂断只认自家 Activity 实例的信道）——
         * 通话页 standard 启动被重建（旋转/云测 am start 直启）后，新实例信道是空的，
         * 对它 end() 不发挂断帧，对端通话占线假死、引擎永不释放。通话落账即挂注册口，
         * 挂断路由一律打活信道；end() 结算先摘口。
         */
        @Volatile
        internal var liveCall: MasterCallChannel? = null

        /** 挂断路由入口：挂活着的通话；无通话在账则静默（与 idle 端语义一致）。 */
        fun endLiveCall() {
            liveCall?.end()
        }

        /** 引擎 Context 注入口（避免把 android Context 混进会话层）。 */
        @Volatile
        var appContext: android.content.Context? = null
    }
}
