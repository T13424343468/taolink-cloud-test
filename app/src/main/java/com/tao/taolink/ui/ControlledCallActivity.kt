package com.tao.tao.link.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tao.tao.link.R
import com.tao.tao.link.call.ControlledCallHost
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * 被控端通话页（被控零操作：来电即接，页面只呈现画面）：
 * 远端大窗 + 本地小窗；事件从 [ControlledCallHost.ui] 单槽订阅（onResume 挂 / onDestroy 清）。
 * 来电时页面不在场也不丢画面——宿主补投通话在场期间的轨（快照挂断即清）。
 */
class ControlledCallActivity : AppCompatActivity(), ControlledCallHost.Listener {

    private var remoteBound: VideoTrack? = null
    private var localBound: VideoTrack? = null
    private var remoteInit = false
    private var localInit = false

    private val remoteRenderer: SurfaceViewRenderer by lazy { findViewById(R.id.call_remote_view) }
    private val localRenderer: SurfaceViewRenderer by lazy { findViewById(R.id.call_local_view) }
    private val statusView: TextView by lazy { findViewById(R.id.call_status) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controlled_call)
        statusView.text = getString(R.string.call_status_connected)
    }

    override fun onResume() {
        super.onResume()
        ControlledCallHost.ui = this
    }

    override fun onDestroy() {
        if (ControlledCallHost.ui === this) {
            ControlledCallHost.ui = null // 只清自己的槽，不覆盖后来者
        }
        remoteBound = null
        localBound = null
        remoteRenderer.release()
        localRenderer.release()
        super.onDestroy()
    }

    override fun onIncomingCall() {
        statusView.text = getString(R.string.call_status_connected)
    }

    override fun onLocalVideoReady(track: VideoTrack, egl: EglBase.Context) {
        runOnUiThread { bindLocal(track, egl) }
    }

    override fun onRemoteVideoReady(track: VideoTrack, egl: EglBase.Context) {
        runOnUiThread { bindRemote(track, egl) }
    }

    override fun onCallEnded() {
        runOnUiThread { finish() }
    }

    /** 本地小窗：镜像显示；轨换绑先摘旧（换轨只在重连时发生）。 */
    private fun bindLocal(track: VideoTrack, egl: EglBase.Context) {
        if (!localInit) {
            localRenderer.init(egl, null)
            localRenderer.setMirror(true)
            localRenderer.setZOrderMediaOverlay(true)
            localInit = true
        }
        localBound?.removeSink(localRenderer)
        localBound = track
        track.addSink(localRenderer)
        localRenderer.visibility = android.view.View.VISIBLE
    }

    private fun bindRemote(track: VideoTrack, egl: EglBase.Context) {
        if (!remoteInit) {
            remoteRenderer.init(egl, null)
            remoteInit = true
        }
        remoteBound?.removeSink(remoteRenderer)
        remoteBound = track
        track.addSink(remoteRenderer)
    }
}
