package com.tao.tao.link.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tao.tao.link.R
import com.tao.tao.link.call.MasterCallChannel
import com.tao.tao.link.net.MasterSessionHolder
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * 主控端通话页（定稿原型：画面页常驻通话入口 → 通话页）：
 * 远端大窗 + 本地小窗（SurfaceViewRenderer，轨与渲染上下文成对交付后 init，避免跨上下文拷贝）。
 * 语音/视频二选一发起，挂断随时可点；会话从 [MasterSessionHolder] 取活会话，
 * 未连接守门直接退场（不产半开会话页）。相机/麦克风运行时权限在此页申请，
 * 被拒时视频引擎退化为纯音频（以人为本：声音先通）。
 */
class MasterCallActivity : AppCompatActivity() {

    private var channel: MasterCallChannel? = null
    private var sessionLive = false

    private var remoteBound: VideoTrack? = null
    private var localBound: VideoTrack? = null
    private var remoteInit = false
    private var localInit = false

    private val remoteRenderer: SurfaceViewRenderer by lazy { findViewById(R.id.call_remote_view) }
    private val localRenderer: SurfaceViewRenderer by lazy { findViewById(R.id.call_local_view) }
    private val statusView: TextView by lazy { findViewById(R.id.call_status) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_master_call)
        MasterCallChannel.appContext = applicationContext

        findViewById<Button>(R.id.btn_call_voice).setOnClickListener { channel?.startVoice() }
        findViewById<Button>(R.id.btn_call_video).setOnClickListener { channel?.startVideo() }
        findViewById<Button>(R.id.btn_call_hangup).setOnClickListener {
            channel?.end()
            finish()
        }
        requestCallPermissions()
    }

    override fun onResume() {
        super.onResume()
        val session = MasterSessionHolder.get()
        if (session == null || !session.isRunning()) {
            Toast.makeText(this, R.string.call_session_not_ready, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sessionLive = true
        val ch = channel ?: MasterCallChannel(
            isSessionLive = { sessionLive && session.isRunning() },
            sendSignal = { payload -> session.sendCallSignal(payload) },
        )
        ch.onLocalVideoReady = { track, egl -> runOnUiThread { bindLocal(track, egl) } }
        ch.onRemoteVideoReady = { track, egl -> runOnUiThread { bindRemote(track, egl) } }
        ch.onCallEnded = {
            sessionLive = false
            runOnUiThread { finish() } // 对端挂断/连接失败：回管理页，不留死通话页
        }
        channel = ch
        session.onCallSignal = ch::onCallSignal
    }

    override fun onDestroy() {
        channel?.end()
        channel = null
        MasterSessionHolder.get()?.onCallSignal = null
        MasterCallChannel.appContext = null
        remoteRenderer.release()
        localRenderer.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALL_PERMISSIONS &&
            grantResults.any { it != PackageManager.PERMISSION_GRANTED }
        ) {
            Toast.makeText(this, R.string.call_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    /** 本地小窗：镜像显示；轨换绑先摘旧（降档不换轨，只在重新发起通话时触发）。 */
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
        statusView.text = getString(R.string.call_status_connected)
    }

    private fun bindRemote(track: VideoTrack, egl: EglBase.Context) {
        if (!remoteInit) {
            remoteRenderer.init(egl, null)
            remoteInit = true
        }
        remoteBound?.removeSink(remoteRenderer)
        remoteBound = track
        track.addSink(remoteRenderer)
        statusView.text = getString(R.string.call_status_connected)
    }

    private fun requestCallPermissions() {
        val needed = PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_CALL_PERMISSIONS)
        }
    }

    private companion object {
        const val REQUEST_CALL_PERMISSIONS = 1001
        val PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}
