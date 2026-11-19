package com.tao.tao.link.call

import android.content.Context
import com.tao.tao.link.LinkLog
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * WebRTC 语音引擎（老板拍板分岔 2 = A，技术路线-决策清单）。
 * 本期 voice first：音频采集（AudioRecord 经 WebRTC audio module）→ 远端渲染；
 * 视频通话（VIDEO 路由 + 摄像头采集）留接口与降级位，本轮不实现。
 * 信令（offer/answer/ICE）不在这里发——由持有会话的一方经 CallSignalSink 注入既有加密会话（AUDIO 路由帧）。
 */
class WebRtcVoiceEngine(
    private val context: Context,
    private val isInitiator: Boolean,
    private val signalSink: CallSignalSink,
    private val onRemoteAudioReady: () -> Unit,
    private val onHangup: () -> Unit,
) : PeerConnection.Observer {

    /** 信令出口：把 SDP/ICE 交给会话层发送（MasterSession/被控 connection）。 */
    fun interface CallSignalSink {
        fun sendSignal(payload: ByteArray)
    }

    private val rootEglBase: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioReceived = false

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

        val source = factory!!.createAudioSource(MediaConstraints())
        audioSource = source
        localAudioTrack = factory!!.createAudioTrack(AUDIO_TRACK_ID, source)
        pc.addTrack(localAudioTrack!!, listOf(AUDIO_STREAM_ID))

        if (isInitiator) createAndSendOffer(pc)
    }

    /** 对端信令入口（会话层 AUDIO 帧路由到这里）。 */
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
        // 先摘账再关闸：pc.close() 若同步回调 onHangup 重入 end()，账已空防原生层双重 close（云跑实弹 2026-11-19 教训）
        val pc = peerConnection
        peerConnection = null
        try { pc?.close() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioSource = null
        localAudioTrack = null
        remoteAudioReceived = false
        LinkLog.d(TAG, "引擎已释放")
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

    override fun onAddStream(stream: MediaStream) {
        if (stream.audioTracks.isNotEmpty()) {
            remoteAudioReceived = true
            onRemoteAudioReady()
        }
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
        private const val TAG = "WebRtcVoice"
        private const val AUDIO_TRACK_ID = "taolink-audio"
        private const val AUDIO_STREAM_ID = "taolink-audio-stream"
    }
}
