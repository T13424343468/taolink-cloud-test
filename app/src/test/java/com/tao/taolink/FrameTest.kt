package com.tao.tao.link

import com.tao.tao.link.protocol.Frame
import com.tao.tao.link.protocol.FrameType
import com.tao.tao.link.protocol.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTest {

    @Test
    fun encodeThenDecode_roundtrip_payloadIntact() {
        val frame = Frame(Route.CONTROL, FrameType.COMMAND, 42L, byteArrayOf(1, 2, 3))
        val decoded = Frame.decode(Frame.encode(frame))
        assertEquals(Route.CONTROL, decoded!!.route)
        assertEquals(FrameType.COMMAND, decoded.type)
        assertEquals(42L, decoded.seq)
        assertTrue(decoded.payload.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun decode_versionMismatch_rejected() {
        val bytes = Frame.encode(Frame(Route.CONTROL, FrameType.COMMAND, 1L, byteArrayOf(7)))
        bytes[4] = 9 // 版本字节
        assertNull(Frame.decode(bytes))
    }

    @Test
    fun decode_tooShortInput_returnsNullNotThrow() {
        assertNull(Frame.decode(ByteArray(5)))
    }

    @Test
    fun decode_unknownRouteOrType_returnsNullNotThrow() {
        val bytes = Frame.encode(Frame(Route.CONTROL, FrameType.COMMAND, 1L, byteArrayOf(7)))
        bytes[5] = 99
        assertNull(Frame.decode(bytes))
        bytes[5] = Route.CONTROL.id.toByte()
        bytes[6] = 99
        assertNull(Frame.decode(bytes))
    }

    @Test
    fun encode_zeroPayload_frameLengthHeaderOnly() {
        val frame = Frame(Route.STATUS, FrameType.HEARTBEAT, 0L, ByteArray(0))
        val encoded = Frame.encode(frame)
        assertEquals(11, encoded.size)
    }
}
