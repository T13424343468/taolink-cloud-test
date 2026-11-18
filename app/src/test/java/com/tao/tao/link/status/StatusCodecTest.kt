package com.tao.tao.link.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 状态载荷编解码考卷（协议文档 §2-9 布局：1B 在线 + 1B 电量 + 8B 剩余 + 8B 总量，大端）。 */
class StatusCodecTest {

    @Test
    fun `encode then parse round trips`() {
        val status = StatusChannel.PeerStatus(
            online = true,
            batteryPercent = 87,
            storageFreeBytes = 0x01_02_03_04_05_06_07_08L,
            storageTotalBytes = 0xF0_F1_F2_F3_F4_F5_F6_F7uL.toLong(),
        )
        val parsed = StatusCodec.parse(StatusCodec.encode(status))!!
        assertEquals(true, parsed.online)
        assertEquals(87, parsed.batteryPercent)
        assertEquals(0x01_02_03_04_05_06_07_08L, parsed.storageFreeBytes)
        assertEquals(0xF0_F1_F2_F3_F4_F5_F6_F7uL.toLong(), parsed.storageTotalBytes)
    }

    @Test
    fun `offline encodes to zero flag`() {
        val status = StatusChannel.PeerStatus(false, 50, 1L, 2L)
        val bytes = StatusCodec.encode(status)
        assertEquals(0.toByte(), bytes[0])
        assertEquals(false, StatusCodec.parse(bytes)!!.online)
    }

    @Test
    fun `battery clamped to 0-100`() {
        val high = StatusCodec.parse(StatusCodec.encode(StatusChannel.PeerStatus(true, 200, 1L, 2L)))!!
        val low = StatusCodec.parse(StatusCodec.encode(StatusChannel.PeerStatus(true, -5, 1L, 2L)))!!
        assertEquals(100, high.batteryPercent)
        assertEquals(0, low.batteryPercent)
    }

    @Test
    fun `parse rejects wrong payload size`() {
        assertNull(StatusCodec.parse(ByteArray(17)))
        assertNull(StatusCodec.parse(ByteArray(0)))
    }
}
