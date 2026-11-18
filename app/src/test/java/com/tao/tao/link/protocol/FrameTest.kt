package com.tao.tao.link.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 帧编解码考卷（协议文档 §0：11 字节帧头 + 载荷，版本不符即拒）。 */
class FrameTest {

    @Test
    fun `encode then decode round trips`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val frame = Frame(Route.CONTROL, FrameType.COMMAND, 0x0102_0304L, payload)
        val decoded = Frame.decode(Frame.encode(frame))!!
        assertEquals(Route.CONTROL, decoded.route)
        assertEquals(FrameType.COMMAND, decoded.type)
        assertEquals(0x0102_0304L, decoded.seq)
        assertEquals(payload.toList(), decoded.payload.toList())
    }

    @Test
    fun `empty payload round trips`() {
        val frame = Frame(Route.STATUS, FrameType.HEARTBEAT, 7L, ByteArray(0))
        val decoded = Frame.decode(Frame.encode(frame))!!
        assertEquals(0, decoded.payload.size)
        assertEquals(7L, decoded.seq)
    }

    @Test
    fun `decode rejects wrong version and short data`() {
        val good = Frame.encode(Frame(Route.SCREEN, FrameType.CHUNK, 1L, byteArrayOf(9)))
        good[4] = 2 // 版本位改坏
        assertNull(Frame.decode(good))
        assertNull(Frame.decode(ByteArray(5)))
    }

    @Test
    fun `decode rejects unknown route and type`() {
        val bad = Frame.encode(Frame(Route.CONTROL, FrameType.ACK, 1L, ByteArray(0)))
        bad[5] = 99
        assertNull(Frame.decode(bad))
        val badType = Frame.encode(Frame(Route.CONTROL, FrameType.ACK, 1L, ByteArray(0)))
        badType[6] = 99
        assertNull(Frame.decode(badType))
    }
}
