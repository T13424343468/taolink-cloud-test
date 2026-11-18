package com.tao.tao.link.protocol

/**
 * 心跳与自愈策略（协议文档 §3，稳定优先一等公民）。
 * 阈值集中一处（编码规范一.5）；重连时限基线由研发班定后落档覆盖。
 */
object HeartbeatPolicy {
    /** 心跳间隔（毫秒）。 */
    const val INTERVAL_MS: Long = 5_000

    /** 超时判定线（毫秒）：超过即进入自愈。 */
    const val TIMEOUT_MS: Long = 15_000

    /** 重连起步退避（毫秒）。 */
    const val BACKOFF_START_MS: Long = 1_000

    /** 重连退避封顶（毫秒）。 */
    const val BACKOFF_CAP_MS: Long = 30_000
}
