package com.tao.tao.link.status

/**
 * 状态查看契约（协议文档 §2-9）：在线/电量/存储，随心跳捎带，不单独建连。
 * 数据只在已有心跳帧上搭载，禁止为状态查看另开连接（稳定优先）。
 */
interface StatusChannel {
    data class PeerStatus(
        val online: Boolean,
        val batteryPercent: Int,
        val storageFreeBytes: Long,
        val storageTotalBytes: Long,
    )

    /** 生成随心跳帧捎带的本端状态载荷。 */
    fun snapshot(): PeerStatus

    /** 从心跳帧解析对端捎带状态；非状态帧返回 null。 */
    fun parse(heartbeatPayload: ByteArray): PeerStatus?
}
