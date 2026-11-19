package com.tao.tao.link.net

import com.tao.tao.link.LinkLog
import com.tao.tao.link.control.CommandAckTracker
import com.tao.tao.link.protocol.DeviceFingerprint
import com.tao.tao.link.protocol.Frame
import com.tao.tao.link.protocol.FrameType
import com.tao.tao.link.protocol.HeartbeatMonitor
import com.tao.tao.link.protocol.HeartbeatPolicy
import com.tao.tao.link.protocol.JsonText
import com.tao.tao.link.protocol.PROTOCOL_VERSION
import com.tao.tao.link.protocol.Route
import com.tao.tao.link.protocol.SelfHealingBackoff
import com.tao.tao.link.protocol.SessionCrypto
import com.tao.tao.link.protocol.SessionState
import com.tao.tao.link.status.StatusCodec
import com.tao.tao.link.status.StatusChannel
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 主控端会话（协议文档 §1-§3 全量落点）：
 * 发起连接、配对握手（6 位码 + 指纹交换）、心跳与自愈重连、指令回执、画面/文件帧收发。
 * 状态唯一来源 = SessionState，迁移只经 transition（封装纪律）；
 * 自愈重连免输码：内存密钥不落盘 + 对端指纹核验，密钥随进程消亡需重配对（协议口径）。
 */
class MasterSession(
    private val localDeviceName: String,
    private val localUniqueId: String,
    private val crypto: SessionCrypto,
    private val statusSnapshot: () -> StatusChannel.PeerStatus,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // ---- 对端参数 ----
    private var peerHost: String = ""
    private var peerPort: Int = 0

    @Volatile
    private var expectedFingerprint: String? = null

    // ---- 回调（UI 侧注入）----
    var onStateChanged: ((SessionState) -> Unit)? = null
    var onPeerStatus: ((StatusChannel.PeerStatus) -> Unit)? = null
    var onScreenFrame: ((ByteArray) -> Unit)? = null
    var onCallSignal: ((ByteArray) -> Unit)? = null
    var onPairCompleted: ((DeviceFingerprint) -> Unit)? = null
    var onCommandGiveUp: ((seq: Long) -> Unit)? = null

    // ---- 运行件 ----
    private val monitor = HeartbeatMonitor(clock)
    private val backoff = SelfHealingBackoff()
    private val seqCounter = AtomicLong(0)
    private val running = AtomicBoolean(false)
    private val secured = AtomicBoolean(false)
    private val stateLock = Any()

    @Volatile
    private var state: SessionState = SessionState.IDLE

    private var socket: Socket? = null
    private var socketOut: BufferedOutputStream? = null
    private val writerQueue = LinkedBlockingQueue<ByteArray>(SEND_QUEUE_CAP)
    private var writerThread: Thread? = null
    private var readerThread: Thread? = null
    private var tickThread: Thread? = null
    private var lastHeartbeatSentMs: Long = 0
    private var lastReconnectAttemptMs: Long = 0

    private val ackTracker = CommandAckTracker(
        clock = clock,
        resend = { pending -> enqueueWire(pending.wireBytes) },
        onGiveUp = { pending ->
            LinkLog.w(TAG, "指令超次放弃 seq=${pending.seq} route=${pending.route}")
            onCommandGiveUp?.invoke(pending.seq)
        },
    )

    // ============================ 对外能力 ============================

    /**
     * 配对并建立会话（阻塞；UI 线程禁调）。成功返回 true，状态停在 CONNECTED。
     * 流程：IDLE→DISCOVERED→PAIRING→握手（明文 HELLO 交换指纹 → 派生密钥 → 加密挑战应答）→CONNECTED。
     */
    fun pairAndConnect(host: String, port: Int, pairCode: CharArray): Boolean {
        check(!running.get()) { "会话已在运行" }
        peerHost = host
        peerPort = port
        expectedFingerprint = null
        transition(SessionState.DISCOVERED)
        if (!transition(SessionState.PAIRING)) return false
        running.set(true)
        return try {
            val local = DeviceFingerprint.of(localDeviceName, localUniqueId)
            val connected = openSocket() ?: return failPairing()
            // 握手读加超时：对端不应答按配对失败收场，不给「永久卡在连接页」留门（双机实弹 2026-11-19 定因）
            connected.soTimeout = HANDSHAKE_TIMEOUT_MS
            val peerHello = handshakeHello(connected, local.hex) ?: return failPairing()
            // 对齐 PairingResponder 契约（被控端单测背书）：PAIR_REQUEST 载荷=「码\t设备名\t指纹hex」；
            // 码错被控端回 PAIR_REJECT，猜码不放行。配对码派生会话密钥的 TLS 补位按协议文档留后续班落位。
            val submit = "${String(pairCode)}\t$localDeviceName\t${local.hex}".toByteArray(Charsets.UTF_8)
            if (!writeDirect(connected, wireBytes(Route.CONTROL, FrameType.PAIR_REQUEST, seqCounter.incrementAndGet(), submit))) {
                return failPairing()
            }
            val reply = readFrame(connected) ?: return failPairing()
            if (reply.type != FrameType.PAIR_CONFIRM) return failPairing()
            // PAIR_CONFIRM 载荷=被控端本端指纹 hex（PairingResponder 契约），对得上才算真连上对端
            val confirm = String(reply.payload, Charsets.UTF_8)
            if (confirm != peerHello.fingerprintHex) return failPairing()
            expectedFingerprint = peerHello.fingerprintHex
            startThreads(connected)
            connected.soTimeout = 0 // 握手毕清超时：心跳读空闲不应被误判断线
            transition(SessionState.CONNECTED)
            monitor.onPeerHeartbeat()
            onPairCompleted?.invoke(DeviceFingerprint(peerHello.fingerprintHex, peerHello.deviceName))
            true
        } finally {
            pairCode.fill('\u0000')
        }
    }

    /** 断线自愈重连入口（需求清单 §3）：凭已配对指纹重连免输码；经 SELF_HEALING 回 CONNECTED。 */
    fun reconnect(host: String, port: Int, fingerprintHex: String) {
        peerHost = host
        peerPort = port
        expectedFingerprint = fingerprintHex
        // IDLE 重入自愈：按状态机走 DISCOVERED→DISCONNECTED→SELF_HEALING，不新增顶层态
        if (state == SessionState.IDLE) transition(SessionState.DISCOVERED)
        if (state == SessionState.DISCOVERED) transition(SessionState.DISCONNECTED)
        if (state == SessionState.DISCONNECTED) transition(SessionState.SELF_HEALING)
        ensureThreads()
    }

    /** 显式断开：停心跳、关连接、回 IDLE。 */
    fun close() {
        running.set(false)
        closeSocket()
        synchronized(stateLock) {
            // 用户显式断开直接归位 IDLE，自愈不再续跑
            state = SessionState.IDLE
        }
        onStateChanged?.invoke(state)
    }

    fun currentState(): SessionState = state

    /**
     * 指令通道发送（带回执追踪）：返回分配的 seq；未连接返回 -1（指令未入队）。
     */
    fun sendTrackedControl(payload: ByteArray): Long {
        if (state != SessionState.CONNECTED && state != SessionState.CONTROLLING) return -1
        val seq = seqCounter.incrementAndGet()
        val wire = wireBytes(Route.CONTROL, FrameType.COMMAND, seq, payload)
        ackTracker.track(CommandAckTracker.PendingCommand(seq, Route.CONTROL, FrameType.COMMAND, payload, wire))
        enqueueWire(wire)
        return seq
    }

    /** 通话信令发送（语音引擎班 2026-11-19；走 AUDIO 路由帧）。 */
    fun sendCallSignal(payload: ByteArray): Long {
        val seq = seqCounter.incrementAndGet()
        LinkLog.d(TAG, "通话信令入队 seq=$seq len=${payload.size} state=$state")
        enqueueWire(wireBytes(Route.AUDIO, FrameType.CALL_SIGNAL, seq, payload))
        return seq
    }

    fun sendFileChunk(payload: ByteArray): Long {
        if (state != SessionState.CONNECTED && state != SessionState.CONTROLLING) return -1
        val seq = seqCounter.incrementAndGet()
        enqueueWire(wireBytes(Route.FILE, FrameType.CHUNK, seq, payload))
        return seq
    }

    fun isRunning(): Boolean = running.get()

    // ============================ 会话内部 ============================

    private fun openSocket(): Socket? = try {
        val s = Socket()
        s.connect(InetSocketAddress(peerHost, peerPort), CONNECT_TIMEOUT_MS)
        s.tcpNoDelay = true
        s
    } catch (e: Exception) {
        LinkLog.w(TAG, "连接失败：$peerHost:$peerPort ${e.message}")
        null
    }

    /** 握手期直写：请求-应答同步收发，不经发送队列（写线程此刻未起跑）。 */
    private fun writeDirect(socket: Socket, bytes: ByteArray): Boolean = try {
        socket.getOutputStream().let { it.write(bytes); it.flush() }
        true
    } catch (e: Exception) {
        LinkLog.w(TAG, "握手直写失败：${e.message}")
        false
    }

    private fun failPairing(): Boolean {
        running.set(false)
        closeSocket()
        transition(SessionState.DISCONNECTED)
        return false
    }

    private fun handshakeHello(socket: Socket, localFingerprintHex: String): HelloPayload? {
        val hello = JsonText.obj(
            JsonText.field("v", PROTOCOL_VERSION),
            JsonText.field("name", localDeviceName),
            JsonText.field("fp", localFingerprintHex),
        ).toByteArray(Charsets.UTF_8)
        // 握手期写线程尚未起跑（startThreads 在配对确认后），队列帧不上链路——握手收发一律同步直写
        if (!writeDirect(socket, wireBytes(Route.CONTROL, FrameType.HELLO, seqCounter.incrementAndGet(), hello))) return null
        val reply = readFrame(socket) ?: return null
        if (reply.type != FrameType.HELLO) return null
        return parseHello(reply.payload)
    }

    private fun parseHello(payload: ByteArray): HelloPayload? = try {
        val obj = JSONObject(String(payload, Charsets.UTF_8))
        val ver = obj.getInt("v")
        if (ver != PROTOCOL_VERSION) {
            LinkLog.w(TAG, "协议版本不符：$ver ≠ $PROTOCOL_VERSION")
            null
        } else {
            HelloPayload(deviceName = obj.getString("name"), fingerprintHex = obj.getString("fp"))
        }
    } catch (e: Exception) {
        LinkLog.w(TAG, "HELLO 解析失败：${e.message}")
        null
    }

    private fun startThreads(socket: Socket) {
        this.socket = socket
        socketOut = BufferedOutputStream(socket.getOutputStream())
        ensureThreads()
        readerThread?.interrupt()
        readerThread = threadLoop("taolink-reader") { readerLoop(socket) }
    }

    private fun ensureThreads() {
        if (writerThread?.isAlive != true) writerThread = threadLoop("taolink-writer") { writerLoop() }
        if (tickThread?.isAlive != true) tickThread = threadLoop("taolink-tick") { tickLoop() }
    }

    private fun writerLoop() {
        while (running.get()) {
            val bytes = try {
                writerQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            } catch (e: InterruptedException) {
                continue
            }
            val out = socketOut ?: continue // 自愈中断链，帧暂存队列，重连后补发
            try {
                out.write(bytes)
                out.flush()
            } catch (e: Exception) {
                LinkLog.w(TAG, "写出失败，进入自愈：${e.message}")
                enterSelfHealing()
            }
        }
    }

    private fun readerLoop(socket: Socket) {
        val input = try {
            DataInputStream(socket.getInputStream())
        } catch (e: Exception) {
            return
        }
        while (running.get() && !socket.isClosed) {
            val frame = try {
                readFrameFrom(input)
            } catch (e: Exception) {
                LinkLog.w(TAG, "读帧中断：${e.message}")
                enterSelfHealing()
                return
            } ?: continue
            dispatch(frame)
        }
    }

    private fun dispatch(frame: Frame) {
        // 对端能送进任何一帧，链路即活：喂狗不绑单一回执格式（STATUS/ACK/心跳都算数）
        monitor.onPeerHeartbeat()
        when (frame.type) {
            FrameType.HEARTBEAT -> {
                monitor.onPeerHeartbeat()
                if (state == SessionState.SELF_HEALING) transition(SessionState.CONNECTED)
                sendFrame(Route.CONTROL, FrameType.ACK, ackPayload(frame.seq))
                StatusCodec.parse(frame.payload)?.let { onPeerStatus?.invoke(it) }
            }
            FrameType.ACK -> {
                val acked = parseAckSeq(frame.payload)
                if (acked != null) {
                    monitor.onPeerHeartbeat()
                    if (state == SessionState.SELF_HEALING) transition(SessionState.CONNECTED)
                    ackTracker.onAck(acked)
                }
            }
            FrameType.CALL_SIGNAL ->
                when (frame.route) {
                    Route.AUDIO -> {
                        LinkLog.d(TAG, "收到通话信令 len=${frame.payload.size}")
                        onCallSignal?.invoke(frame.payload) ?: LinkLog.w(TAG, "通话信令无处投递：onCallSignal 未接线")
                    }
                    else -> LinkLog.d(TAG, "帧暂未处理 route=${frame.route}")
                }
            FrameType.CHUNK ->
                when (frame.route) {
                    Route.SCREEN -> onScreenFrame?.invoke(frame.payload)
                    else -> LinkLog.d(TAG, "帧暂未处理 route=${frame.route}")
                }
            else -> LinkLog.d(TAG, "帧暂未处理 route=${frame.route} type=${frame.type}")
        }
    }

    private fun tickLoop() {
        while (running.get()) {
            try {
                tickOnce()
            } catch (e: Exception) {
                // 看门狗线程不许带崩进程：单轮异常记日志下轮再来（双机实弹 2026-11-19 教训）
                LinkLog.w(TAG, "tick 单轮异常：${e.message}")
            }
            try {
                Thread.sleep(TICK_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun tickOnce() {
        val now = clock()
            // 1) 心跳：到点即发（载荷 = 本端状态捎带，StatusChannel 契约）
            if ((state == SessionState.CONNECTED || state == SessionState.CONTROLLING) &&
                now - lastHeartbeatSentMs >= HeartbeatPolicy.INTERVAL_MS
            ) {
                lastHeartbeatSentMs = now
                sendFrame(Route.CONTROL, FrameType.HEARTBEAT, StatusCodec.encode(statusSnapshot()))
            }
            // 2) 看门狗：超时线触发自愈
            if ((state == SessionState.CONNECTED || state == SessionState.CONTROLLING) && monitor.isTimedOut()) {
                enterSelfHealing()
            }
            // 3) 自愈：按退避节奏重连
            if (state == SessionState.SELF_HEALING && now - lastReconnectAttemptMs >= backoff.nextDelayMs()) {
                lastReconnectAttemptMs = now
                attemptReconnect()
            }
        // 4) 指令回执：超时重发不重放
        ackTracker.tick()
    }

    private fun attemptReconnect() {
        if (expectedFingerprint == null) {
            LinkLog.w(TAG, "无已配对指纹，自愈终止（重配对后才可重连）")
            return
        }
        val connected = openSocket() ?: return
        connected.soTimeout = HANDSHAKE_TIMEOUT_MS
        val local = DeviceFingerprint.of(localDeviceName, localUniqueId)
        val hello = handshakeHello(connected, local.hex)
        if (hello == null) {
            closeSocketQuietly(connected)
            return
        }
        if (hello.fingerprintHex != expectedFingerprint) {
            LinkLog.w(TAG, "对端指纹不符，拒绝重连（防冒充）")
            closeSocketQuietly(connected)
            return
        }
        // 重连免输码：内存密钥仍在（密钥不落盘口径）。
        // 0.2.4 口径：密钥派生留后续班（被控端解密层未落位），会话保持明文帧；
        // 此前此处 secured.set(true) 后发加密心跳，密钥未派生掷异常杀进程（双机实弹 2026-11-19 定因）。
        startThreads(connected)
        connected.soTimeout = 0
        sendFrame(Route.CONTROL, FrameType.HEARTBEAT, StatusCodec.encode(statusSnapshot()))
        if (state == SessionState.SELF_HEALING) {
            transition(SessionState.CONNECTED)
            backoff.reset()
        }
    }

    private fun enterSelfHealing() {
        if (state == SessionState.SELF_HEALING) return
        closeSocket()
        monitor.enterSelfHealing()
        lastReconnectAttemptMs = 0
        transition(SessionState.SELF_HEALING)
        ensureThreads()
    }

    // ============================ 帧收发 ============================

    private fun sendFrame(route: Route, type: FrameType, payload: ByteArray) {
        val seq = seqCounter.incrementAndGet()
        enqueueWire(wireBytes(route, type, seq, payload))
    }

    private fun wireBytes(route: Route, type: FrameType, seq: Long, payload: ByteArray): ByteArray {
        val body = if (secured.get()) crypto.encrypt(payload) else payload
        return Frame.encode(Frame(route, type, seq, body))
    }

    private fun enqueueWire(bytes: ByteArray) {
        if (!writerQueue.offer(bytes)) {
            // 弱网降档口（协议 §3）：队列满先丢帧，控制指令由回执追踪重发补位，画面/文件块由上层续传
            LinkLog.w(TAG, "发送队列满，帧丢弃")
        }
    }

    private fun readFrame(socket: Socket): Frame? = try {
        readFrameFrom(DataInputStream(socket.getInputStream()))
    } catch (e: Exception) {
        LinkLog.w(TAG, "读帧失败：${e.message}")
        null
    }

    private fun readFrameFrom(input: DataInputStream): Frame? {
        val len = input.readInt()
        if (len < FRAME_HEADER_LEN || len > MAX_FRAME_BYTES) {
            LinkLog.w(TAG, "非法帧长：$len")
            return null
        }
        val data = ByteArray(len)
        input.readFully(data)
        val frame = Frame.decode(data) ?: run {
            LinkLog.w(TAG, "帧解码失败（版本或路由不符）")
            return null
        }
        if (secured.get()) {
            val plain = try {
                crypto.decrypt(frame.payload)
            } catch (e: Exception) {
                LinkLog.w(TAG, "载荷解密失败：${e.message}")
                return null
            }
            return Frame(frame.route, frame.type, frame.seq, plain)
        }
        return frame
    }

    /** ACK 载荷：1B 路由 + 8B 大端 seq。 */
    private fun ackPayload(seq: Long): ByteArray {
        val out = ByteArray(9)
        out[0] = Route.CONTROL.id.toByte()
        for (i in 0 until 8) out[1 + i] = (seq ushr ((7 - i) * 8)).toByte()
        return out
    }

    private fun parseAckSeq(payload: ByteArray): Long? {
        if (payload.size != 9) return null
        var seq = 0L
        for (i in 0 until 8) seq = (seq shl 8) or (payload[1 + i].toLong() and 0xFF)
        return seq
    }

    // ============================ 状态机 ============================

    private fun transition(next: SessionState): Boolean {
        synchronized(stateLock) {
            if (!state.canTransitionTo(next)) {
                LinkLog.w(TAG, "非法状态迁移 ${state}→$next，维持原态")
                return false
            }
            state = next
        }
        onStateChanged?.invoke(next)
        return true
    }

    private fun closeSocketQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: Exception) {
            LinkLog.d(TAG, "关闭对端连接异常：${e.message}")
        }
    }

    private fun closeSocket() {
        val s = socket
        socket = null
        socketOut = null
        writerQueue.clear()
        if (s != null) closeSocketQuietly(s)
        secured.set(false)
    }

    private fun threadLoop(name: String, body: () -> Unit): Thread =
        Thread(body, name).apply {
            isDaemon = true
            start()
        }

    private data class HelloPayload(val deviceName: String, val fingerprintHex: String)

    companion object {
        private const val TAG = "MasterSession"
        private const val TICK_INTERVAL_MS = 250L
        private const val CONNECT_TIMEOUT_MS = 5_000

        /** 握手单帧读超时：对端应答时限，超时按配对失败收场。 */
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val CHALLENGE_LEN = 8
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
        private const val SEND_QUEUE_CAP = 1024

        /** 帧头长度（协议文档 §0：4B 长度 + 7B 头）。 */
        const val FRAME_HEADER_LEN = 11
    }
}
