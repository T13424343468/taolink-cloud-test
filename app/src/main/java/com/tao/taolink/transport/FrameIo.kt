package com.tao.tao.link.transport

import com.tao.tao.link.protocol.Frame
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 帧流读写：把 Frame 编解码接到流上（协议文档 §0 帧格式，4B 长度 + 11B 头 + 载荷）。
 * 只搬字节不解释；解释在会话层。读失败按断线处理（返回 null，不拿异常当控制流）。
 */
object FrameIo {

    fun readFrame(input: InputStream): Frame? {
        val data = DataInputStream(input)
        return try {
            val len = data.readInt()
            if (len <= 0 || len > MAX_FRAME_BYTES) return null
            val body = ByteArray(len - 4)
            data.readFully(body)
            val full = ByteArray(len)
            // 头四字节已在长度域，重拼一版再解码，避免重复实现帧头布局
            val lenBytes = byteArrayOf(
                (len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte(),
            )
            lenBytes.copyInto(full)
            body.copyInto(full, 4)
            Frame.decode(full)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun writeFrame(output: OutputStream, frame: Frame): Boolean {
        return try {
            DataOutputStream(output).let { data ->
                val bytes = Frame.encode(frame)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
}
