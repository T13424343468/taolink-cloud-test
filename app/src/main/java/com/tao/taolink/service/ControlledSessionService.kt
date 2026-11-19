package com.tao.tao.link.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.tao.tao.link.R
import com.tao.tao.link.call.ControlledCallHost
import com.tao.tao.link.control.AccessibilityControlExecutor
import com.tao.tao.link.control.ControlDispatcher
import com.tao.tao.link.protocol.DeviceFingerprint
import com.tao.tao.link.protocol.DeviceIdentity
import com.tao.tao.link.protocol.Frame
import com.tao.tao.link.protocol.FrameType
import com.tao.tao.link.protocol.HeartbeatMonitor
import com.tao.tao.link.protocol.HeartbeatPolicy
import com.tao.tao.link.protocol.PairingRecordStore
import com.tao.tao.link.protocol.PairingResponder
import com.tao.tao.link.protocol.PathType
import com.tao.tao.link.protocol.Route
import com.tao.tao.link.protocol.SelfHealingBackoff
import com.tao.tao.link.protocol.SessionState
import com.tao.tao.link.status.AndroidStatusProvider
import com.tao.tao.link.status.StatusCodec
import com.tao.tao.link.transport.SessionConnection
import com.tao.tao.link.transport.SessionServer
import com.tao.tao.link.ui.ControlledCallActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 被控端驻留前台服务（需求清单 §2 驻留项）：会话监听 + 配对应答 + 心跳应答 + 状态捎带 + 指令执行闭环。
 * 心跳间隔/超时/退避阈值只读 HeartbeatPolicy 一处（编码规范一.5）。
 */
class ControlledSessionService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private var server: SessionServer? = null
    private var heartbeatMonitor: HeartbeatMonitor? = null
    private var selfHealing: SelfHealingBackoff? = null
    private var state: SessionState = SessionState.IDLE
    private val statusProvider by lazy { AndroidStatusProvider(this) }
    private val controlExecutor = AccessibilityControlExecutor()
    private var pairingResponder: PairingResponder? = null
    private var activeConnection: SessionConnection? = null

    /** 最近连接的对端指纹（配对确认后记，连接回填用；Y4 拍板）。 */
    @Volatile
    private var connectedPeerFingerprintHex: String? = null

    /** 被控端通话宿主（引擎生命周期挂服务；CALL_SIGNAL 帧经桥进出引擎，语音引擎班留口本班接线）。 */
    private var callHost: ControlledCallHost? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundAsResident()
        pairingResponder = PairingResponder(
            DeviceIdentity.ownFingerprint(filesDir, getString(R.string.default_device_name)),
            PairingRecordStore(filesDir),
        )
        server = SessionServer(SERVICE_PORT) { connection ->
            executor.execute {
                activeConnection = connection
                transitionTo(SessionState.DISCOVERED)
                connection.startReading { frame -> executor.execute { handleFrame(connection, frame) } }
            }
        }
        server?.start()
        startHeartbeatLoop()
        callHost = ControlledCallHost(this, { payload -> sendCallSignal(payload) }) { notifyIncomingCall() }
    }

    override fun onDestroy() {
        server?.stop()
        callHost?.end()
        callHost = null
        executor.shutdownNow()
        super.onDestroy()
    }

    /** 通话信令回程（引擎应答 SDP/ICE/挂断）：走既有加密会话 AUDIO 路由帧，seq 自增。 */
    private fun sendCallSignal(payload: ByteArray) {
        val connection = activeConnection ?: return
        val seq = callSeqCounter.incrementAndGet()
        connection.send(Frame(Route.AUDIO, FrameType.CALL_SIGNAL, seq, payload))
    }

    /** 来电轻提示（被控零操作，通话已自动接起）：点开才见画面，不点不打扰。 */
    private fun notifyIncomingCall() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ControlledCallActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getText(R.string.resident_title))
            .setContentText(getText(R.string.call_incoming_notify))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .build()
        manager.notify(INCOMING_CALL_NOTIFICATION_ID, notification)
    }

    /** 心跳循环：应答对端心跳、监测超时；超时进自愈态，退避间隔按 HeartbeatPolicy 起步 1s 封顶 30s。 */
    private fun startHeartbeatLoop() {
        heartbeatMonitor = HeartbeatMonitor()
        selfHealing = SelfHealingBackoff()
        executor.execute {
            while (!executor.isShutdown) {
                val monitor = heartbeatMonitor ?: break
                val healing = selfHealing ?: break
                if (monitor.isTimedOut()) {
                    if (!monitor.isSelfHealing) {
                        monitor.enterSelfHealing()
                        transitionTo(SessionState.SELF_HEALING)
                    }
                    // 自愈期静默等待对端重连；对端心跳再次闻到即退出自愈（HeartbeatMonitor.onPeerHeartbeat）
                    Thread.sleep(healing.nextDelayMs())
                } else {
                    healing.reset()
                    Thread.sleep(HeartbeatPolicy.INTERVAL_MS)
                }
            }
        }
    }

    private fun transitionTo(next: SessionState) {
        if (state.canTransitionTo(next)) {
            state = next
        }
    }

    private fun handleFrame(connection: SessionConnection, frame: Frame) {
        when (frame.type) {
            FrameType.HEARTBEAT -> {
                heartbeatMonitor?.onPeerHeartbeat()
                val wasConnected = state == SessionState.CONNECTED
                if (state == SessionState.SELF_HEALING) {
                    transitionTo(SessionState.CONNECTED)
                    selfHealing?.reset()
                } else {
                    transitionTo(SessionState.CONNECTED)
                }
                // 连接成立即回填（Y4 拍板）：最后连接时间+路径类型；本班落码仅直连Socket，路径按 LAN 记（打洞/中继通道落码时随传输回填）
                if (!wasConnected && state == SessionState.CONNECTED) {
                    connectedPeerFingerprintHex?.let {
                        pairingResponder?.markConnected(it, PathType.LAN, System.currentTimeMillis())
                    }
                }
                // 状态随心跳捎带（协议 §2-9），不单独建连
                connection.send(Frame(Route.STATUS, FrameType.STATUS, frame.seq, StatusCodec.encode(statusProvider.snapshot(online = true))))
                connection.send(Frame(Route.CONTROL, FrameType.ACK, frame.seq, HEARTBEAT_ACK))
            }
            FrameType.PAIR_REQUEST -> {
                val reply = handlePairRequest(frame.payload)
                connection.send(Frame(Route.CONTROL, FrameType.PAIR_CONFIRM, frame.seq, reply))
            }
            FrameType.COMMAND -> {
                val ok = ControlDispatcher.dispatch(frame.payload, controlExecutor)
                connection.send(Frame(Route.CONTROL, FrameType.ACK, frame.seq, if (ok) ACK_OK else ACK_FAIL))
            }
            FrameType.CALL_SIGNAL -> if (frame.route == Route.AUDIO) {
                callHost?.onCallSignal(frame.payload)
            }
            else -> {
                // 画面/文件通道由对位垂直实现接手，本班不透传不明帧
            }
        }
    }

    /** 配对请求载荷：主控码 + 对端设备名 + 对端指纹 hex（Tab 分隔）；码错回执拒绝不回环。 */
    private fun handlePairRequest(payload: ByteArray): ByteArray {
        val parts = String(payload, Charsets.UTF_8).split('\t')
        if (parts.size != 3) return PAIR_REJECT
        val responder = pairingResponder ?: return PAIR_REJECT
        val confirm = responder.onSubmit(
            parts[0],
            DeviceFingerprint(parts[2], parts[1]),
            System.currentTimeMillis(),
        )
        transitionTo(SessionState.PAIRING)
        if (confirm != null) connectedPeerFingerprintHex = parts[2]
        return confirm?.toByteArray(Charsets.UTF_8) ?: PAIR_REJECT
    }

    private fun startForegroundAsResident() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getText(R.string.resident_channel_name), NotificationManager.IMPORTANCE_MIN),
            )
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getText(R.string.resident_title))
                .setContentText(getText(R.string.resident_text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val SERVICE_PORT = 48190
        private const val CHANNEL_ID = "taolink_resident"
        private const val NOTIFICATION_ID = 48190
        private const val INCOMING_CALL_NOTIFICATION_ID = 48191

        /** 被控端主动发帧的序号源（应答信令回程用；主控端回执由对端 seq 对账，不冲突）。 */
        private val callSeqCounter = AtomicLong(0)

        private val HEARTBEAT_ACK = byteArrayOf(1)
        private val ACK_OK = byteArrayOf(0)
        private val ACK_FAIL = byteArrayOf(1)
        private val PAIR_REJECT = byteArrayOf(0)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ControlledSessionService::class.java))
        }
    }
}
