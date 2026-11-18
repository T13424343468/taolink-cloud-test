package com.tao.tao.link.status

/**
 * 状态载荷编解码（协议文档 §2-9：随心跳捎带，不单独建连）。
 * 载荷布局：1B 在线 + 1B 电量(0-100) + 8B 存储剩余 + 8B 存储总量（大端）。
 * 纯逻辑可测；parse 对非状态帧返回 null（StatusChannel 契约口径）。
 */
object StatusCodec {

    private const val PAYLOAD_LEN = 1 + 1 + 8 + 8
    private const val ONLINE_TRUE: Byte = 1

    fun encode(status: StatusChannel.PeerStatus): ByteArray {
        val out = ByteArray(PAYLOAD_LEN)
        out[0] = if (status.online) ONLINE_TRUE else 0
        out[1] = status.batteryPercent.coerceIn(0, 100).toByte()
        putLong(out, 2, status.storageFreeBytes)
        putLong(out, 10, status.storageTotalBytes)
        return out
    }

    fun parse(heartbeatPayload: ByteArray): StatusChannel.PeerStatus? {
        if (heartbeatPayload.size != PAYLOAD_LEN) return null
        return StatusChannel.PeerStatus(
            online = heartbeatPayload[0] == ONLINE_TRUE,
            batteryPercent = heartbeatPayload[1].toInt() and 0xFF,
            storageFreeBytes = readLong(heartbeatPayload, 2),
            storageTotalBytes = readLong(heartbeatPayload, 10),
        )
    }

    private fun putLong(out: ByteArray, offset: Int, value: Long) {
        for (i in 0 until Long.SIZE_BYTES) {
            out[offset + i] = (value ushr ((Long.SIZE_BYTES - 1 - i) * 8)).toByte()
        }
    }

    private fun readLong(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until Long.SIZE_BYTES) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return value
    }
}
