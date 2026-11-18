package com.tao.tao.link.protocol

/**
 * 心跳监测（协议文档 §3：稳定优先一等公民）。
 * 被控端应答口：收到对端 HEARTBEAT 帧即回 ACK 并刷新对端在线时刻；
 * 超过 TIMEOUT_MS 未闻对端心跳 → 进入自愈（SELF_HEALING），按 HeartbeatPolicy 指数退避重连。
 * 时间由注入时钟提供，纯逻辑可测。
 */
class HeartbeatMonitor(private val clock: () -> Long = System::currentTimeMillis) {

    /** 是否已进入自愈态（由会话层据此切 SessionState.SELF_HEALING）。 */
    var isSelfHealing: Boolean = false
        private set

    private var lastPeerBeatAtMs: Long = clock()

    /** 收到对端心跳：刷新在线时刻；退出自愈态（成功重连即归位）。 */
    fun onPeerHeartbeat() {
        lastPeerBeatAtMs = clock()
        isSelfHealing = false
    }

    /** 距上次闻对端心跳已过毫秒数。 */
    fun elapsedSincePeerMs(): Long = clock() - lastPeerBeatAtMs

    /** 是否超时（超过 HeartbeatPolicy.TIMEOUT_MS 未闻对端）。 */
    fun isTimedOut(): Boolean = elapsedSincePeerMs() >= HeartbeatPolicy.TIMEOUT_MS

    /** 由会话层在检测到超时时调用：进入自愈态。幂等。 */
    fun enterSelfHealing() {
        isSelfHealing = true
    }
}
