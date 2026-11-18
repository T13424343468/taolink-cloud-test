package com.tao.tao.link.protocol

/** 帧路由：多路复用的逻辑通道（协议文档 §0）。 */
enum class Route(val id: Int) {
    CONTROL(1),
    SCREEN(2),
    FILE(3),
    AUDIO(4),
    VIDEO(5),
    STATUS(6);

    companion object {
        fun fromId(id: Int): Route = entries.first { it.id == id }
    }
}

/** 帧类型。 */
enum class FrameType(val id: Int) {
    HELLO(1),
    PAIR_REQUEST(2),
    PAIR_CONFIRM(3),
    HEARTBEAT(4),
    COMMAND(5),
    CHUNK(6),
    STATUS(7),
    CALL_SIGNAL(8),
    ACK(9);

    companion object {
        fun fromId(id: Int): FrameType = entries.first { it.id == id }
    }
}

/**
 * 传输帧：4 字节大端长度 + 1 字节版本 + 1 字节 route + 1 字节 type + 4 字节 seq + 载荷。
 * 二进制载荷（画面/音频/文件块）原样透传不转码（协议文档 §0）。
 */
data class Frame(
    val route: Route,
    val type: FrameType,
    val seq: Long,
    val payload: ByteArray,
) {
    companion object {
        private const val HEADER_LEN = 11
        private const val VERSION_BYTE = 1

        fun encode(frame: Frame): ByteArray {
            val out = ByteArray(HEADER_LEN + frame.payload.size)
            val len = out.size
            out[0] = (len ushr 24).toByte()
            out[1] = (len ushr 16).toByte()
            out[2] = (len ushr 8).toByte()
            out[3] = len.toByte()
            out[4] = VERSION_BYTE.toByte()
            out[5] = frame.route.id.toByte()
            out[6] = frame.type.id.toByte()
            out[7] = (frame.seq ushr 24).toByte()
            out[8] = (frame.seq ushr 16).toByte()
            out[9] = (frame.seq ushr 8).toByte()
            out[10] = frame.seq.toByte()
            frame.payload.copyInto(out, HEADER_LEN)
            return out
        }

        /** 解码失败返回 null，由调用方按断线策略处理，不抛异常做控制流。 */
        fun decode(data: ByteArray): Frame? {
            if (data.size < HEADER_LEN) return null
            val version = data[4].toInt() and 0xFF
            if (version != VERSION_BYTE) return null
            val route = Route.entries.firstOrNull { it.id == data[5].toInt() } ?: return null
            val type = FrameType.entries.firstOrNull { it.id == data[6].toInt() } ?: return null
            val seq = ((data[7].toInt() and 0xFF) shl 24) or
                ((data[8].toInt() and 0xFF) shl 16) or
                ((data[9].toInt() and 0xFF) shl 8) or
                (data[10].toInt() and 0xFF)
            val payload = data.copyOfRange(HEADER_LEN, data.size)
            return Frame(route, type, seq.toLong(), payload)
        }
    }
}
