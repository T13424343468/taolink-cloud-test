package com.tao.tao.link.protocol

/**
 * 自愈重连指数退避（协议文档 §3 + 任务令：1s 起步、封顶 30s，按骨架 HeartbeatPolicy）。
 * 纯逻辑可测；阈值只读 HeartbeatPolicy 一处（编码规范一.5）。
 */
class SelfHealingBackoff {

    private var currentMs: Long = HeartbeatPolicy.BACKOFF_START_MS

    /** 下一次重连前要等多少毫秒；连续取值按指数翻倍，至 BACKOFF_CAP_MS 封顶。 */
    fun nextDelayMs(): Long {
        val delay = currentMs
        currentMs = (currentMs * 2).coerceAtMost(HeartbeatPolicy.BACKOFF_CAP_MS)
        return delay
    }

    /** 重连成功即归零，回到起步退避。 */
    fun reset() {
        currentMs = HeartbeatPolicy.BACKOFF_START_MS
    }
}
