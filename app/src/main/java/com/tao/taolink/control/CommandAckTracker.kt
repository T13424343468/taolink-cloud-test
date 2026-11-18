package com.tao.tao.link.control

import com.tao.tao.link.protocol.FrameType
import com.tao.tao.link.protocol.Route

/**
 * 指令回执追踪（协议文档 §2-5：指令带 seq，对端回执 ACK，丢失重发不重放）。
 * 重发复用原 seq 与原线上字节（同一密文重发，不产生新 IV）；超 MAX_ATTEMPTS 次放弃并上报。
 * 时间由注入时钟提供，纯逻辑可测；阈值集中本文件（编码规范一.5）。
 */
class CommandAckTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val resend: (PendingCommand) -> Unit,
    private val onGiveUp: (PendingCommand) -> Unit,
) {

    /** 待回执指令：seq + 原始载荷 + 已加密线上帧（重发不重放，直接重发线上帧）。 */
    class PendingCommand(
        val seq: Long,
        val route: Route,
        val type: FrameType,
        val payload: ByteArray,
        val wireBytes: ByteArray,
    ) {
        var attempts: Int = 1
            internal set
        var lastSentMs: Long = 0
            internal set
    }

    private val pending = HashMap<Long, PendingCommand>()

    fun track(cmd: PendingCommand) {
        cmd.lastSentMs = clock()
        pending[cmd.seq] = cmd
    }

    fun onAck(seq: Long) {
        pending.remove(seq)
    }

    /** 会话层周期调用：超时重发，超次放弃。 */
    fun tick() {
        val now = clock()
        val timedOut = pending.values.filter { now - it.lastSentMs >= ACK_TIMEOUT_MS }
        for (cmd in timedOut) {
            if (cmd.attempts >= MAX_ATTEMPTS) {
                pending.remove(cmd.seq)
                onGiveUp(cmd)
            } else {
                cmd.attempts++
                cmd.lastSentMs = now
                resend(cmd)
            }
        }
    }

    fun pendingCount(): Int = pending.size

    fun pendingSeqs(): List<Long> = pending.keys.sorted()

    companion object {
        const val ACK_TIMEOUT_MS = 1_500L
        const val MAX_ATTEMPTS = 3
    }
}
