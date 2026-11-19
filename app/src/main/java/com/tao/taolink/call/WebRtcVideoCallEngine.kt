package com.tao.tao.link.call

import android.content.Context
import com.tao.tao.link.LinkLog
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * WebRTC 视频通话引擎（接口 10 视频通话侧；语音引擎同构复用 PeerConnection 与信令编解码）。
 * 摄像头采集（Camera2）→ 本地预览 + 远端画面（VideoSink 由 UI 注入）；音频链路与语音对讲同路。
 * 信令不在这里发——由持有会话的一方经 CallSignalSink 注入既有加密会话（CALL_SIGNAL 帧，语音班同通道）。
 * 弱网降档在通道层（MasterCallChannel.degradeVideo）：只降视频不降音频（协议文档 §2-10）。
 */
class WebRtcVideoCallEngine(
    private val context: Context,
    private val isInitiator: Boolean,
    private val signalSink: CallSignalSink,
    private val onRemoteVideoTrack: (VideoTrack) -> Unit,
    private val onLocalVideoReady: (VideoTrack) -> Unit,
    private val onHangup: () -> Unit,
) : PeerConnection.Observer {

    /** 信令出口：与 WebRtcVoiceEngine 同构，把 SDP/ICE 交给会话层发送。 */
    fun interface CallSignalSink {
        fun sendSignal(payload: ByteArray)
    }

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var cameraCapturer: CameraVideoCapturer? = null

    /** 渲染上下文与 UI 侧共享（本地预览/远端画面用同一 EglBase，避免跨上下文拷贝）。 */
    val eglBase: EglBase by lazy { EglBase.create() }

    fun start() {
        val initializationOptions = PeerConnectionFactory.InitializationOptions
            .builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val audioModule = JavaAudioDeviceModule.builder(context)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(org.webrtc.DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(org.webrtc.DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory!!.createPeerConnection(rtcConfig, this) ?: run {
            LinkLog.w(TAG, "PeerConnection 创建失败")
            onHangup()
            return
        }
        peerConnection = pc

        val audio = factory!!.createAudioSource(MediaConstraints())
        audioSource = audio
        localAudioTrack = factory!!.createAudioTrack(AUDIO_TRACK_ID, audio)
        pc.addTrack(localAudioTrack!!, listOf(AUDIO_STREAM_ID))

        openCamera(pc)
        if (isInitiator) createAndSendOffer(pc)
    }

    /** 摄像头采集：前置优先（视频通话主场景是对面看人）；采集失败不阻断音频通话（以人为本：声音先通）。 */
    private fun openCamera(pc: PeerConnection) {
        val enumerator = Camera2Enumerator(context)
        val frontName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
        if (frontName == null) {
            LinkLog.w(TAG, "无可用摄像头，视频退化为纯语音")
            return
        }
        val capturer = enumerator.createCapturer(frontName, null) ?: return
        cameraCapturer = capturer
        val helper = SurfaceTextureHelper.create("taolink-video-capture", eglBase.eglBaseContext)
        surfaceHelper = helper
        val source = factory!!.createVideoSource(capturer.isScreencast)
        videoSource = source
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
        val track = factory!!.createVideoTrack(VIDEO_TRACK_ID, source)
        localVideoTrack = track
        pc.addTrack(track, listOf(VIDEO_STREAM_ID))
        onLocalVideoReady(track)
    }

    /** 弱网降档：只降视频采集帧率，音频不降（协议 §2-10；通道层 Quality 记账同步）。 */
    fun degradeVideoTo(fps: Int) {
        try {
            cameraCapturer?.changeCaptureFormat(VIDEO_WIDTH, VIDEO_HEIGHT, fps)
            LinkLog.d(TAG, "视频采集降档至 ${fps}fps（音频不变）")
        } catch (e: Exception) {
            LinkLog.w(TAG, "降档失败（保持原档）：${e.message}")
        }
    }

    /** 对端信令入口（会话层 CALL_SIGNAL 帧路由到这里）。 */
    fun onCallSignal(payload: ByteArray) {
        val pc = peerConnection ?: return
        when {
            CallSignalCodec.isHangup(payload) -> {
                LinkLog.d(TAG, "对端挂断")
                onHangup()
            }
            else -> {
                val sdp = CallSignalCodec.parseSdp(payload)
                if (sdp != null) {
                    val type = if (sdp.isOffer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
                    pc.setRemoteDescription(SdpLog("setRemote"), SessionDescription(type, sdp.sdp))
                    if (sdp.isOffer) answerRemote(pc)
                    return
                }
                val ice = CallSignalCodec.parseCandidate(payload)
                if (ice != null) {
                    pc.addIceCandidate(IceCandidate(ice.sdpMid, ice.sdpMLineIndex, ice.candidate))
                }
            }
        }
    }

    fun end() {
        try { cameraCapturer?.stopCapture() } catch (_: Exception) {}
        try { cameraCapturer?.dispose() } catch (_: Exception) {}
        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { peerConnection?.close() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        cameraCapturer = null
        surfaceHelper = null
        videoSource = null
        localVideoTrack = null
        peerConnection = null
        audioSource = null
        localAudioTrack = null
        LinkLog.d(TAG, "视频引擎已释放")
    }

    private fun createAndSendOffer(pc: PeerConnection) {
        pc.createOffer(object : SdpObserver by SdpLog("createOffer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SdpLog("setLocal"), desc)
                signalSink.sendSignal(CallSignalCodec.encodeOffer(desc.description))
            }
        }, MediaConstraints())
    }

    private fun answerRemote(pc: PeerConnection) {
        pc.createAnswer(object : SdpObserver by SdpLog("createAnswer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(SdpLog("setLocal"), desc)
                signalSink.sendSignal(CallSignalCodec.encodeAnswer(desc.description))
            }
        }, MediaConstraints())
    }

    // ---- PeerConnection.Observer ----
    override fun onIceCandidate(candidate: IceCandidate) {
        signalSink.sendSignal(
            CallSignalCodec.encodeCandidate(candidate.sdpMid ?: "0", candidate.sdpMLineIndex, candidate.sdp)
        )
    }

    override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
        val track = transceiver.receiver.track() ?: return
        if (track is VideoTrack) onRemoteVideoTrack(track)
    }

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
        LinkLog.d(TAG, "PeerConnection 状态：$newState")
        if (newState == PeerConnection.PeerConnectionState.FAILED ||
            newState == PeerConnection.PeerConnectionState.CLOSED
        ) {
            onHangup()
        }
    }

    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
    override fun onAddStream(stream: MediaStream) {}
    override fun onRemoveStream(stream: MediaStream) {}
    override fun onDataChannel(dc: org.webrtc.DataChannel) {}
    override fun onRenegotiationNeeded() {}

    private inner class SdpLog(private val where: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() { LinkLog.d(TAG, "$where 成功") }
        override fun onCreateFailure(error: String?) { LinkLog.w(TAG, "$where 创建失败：$error") }
        override fun onSetFailure(error: String?) { LinkLog.w(TAG, "$where 设置失败：$error") }
    }

    companion object {
        private const val TAG = "WebRtcVideo"
        private const val AUDIO_TRACK_ID = "taolink-audio"
        private const val AUDIO_STREAM_ID = "taolink-audio-stream"
        private const val VIDEO_TRACK_ID = "taolink-video"
        private const val VIDEO_STREAM_ID = "taolink-video-stream"

        // 采集基准档：通话场景画质够用为先，流量与功耗让路（大道至简；降档下限在通道层）
        const val VIDEO_WIDTH = 640
        const val VIDEO_HEIGHT = 480
        const val VIDEO_FPS = 24
    }
}
