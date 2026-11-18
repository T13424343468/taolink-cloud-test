package com.tao.tao.link

import com.tao.tao.link.status.StatusCodec
import com.tao.tao.link.status.StatusChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusCodecTest {

    @Test
    fun encodeThenParse_roundtripIntact() {
        val status = StatusChannel.PeerStatus(online = true, batteryPercent = 87, storageFreeBytes = 4_000_000_000L, storageTotalBytes = 8_000_000_000L)
        val parsed = StatusCodec.parse(StatusCodec.encode(status))!!
        assertEquals(true, parsed.online)
        assertEquals(87, parsed.batteryPercent)
        assertEquals(4_000_000_000L, parsed.storageFreeBytes)
        assertEquals(8_000_000_000L, parsed.storageTotalBytes)
    }

    @Test
    fun parse_wrongPayloadLength_returnsNull() {
        assertNull(StatusCodec.parse(ByteArray(17)))
        assertNull(StatusCodec.parse(ByteArray(19)))
        assertNull(StatusCodec.parse(ByteArray(0)))
    }

    @Test
    fun encode_batteryClampedToValidRange() {
        val over = StatusChannel.PeerStatus(online = true, batteryPercent = 250, storageFreeBytes = 0, storageTotalBytes = 0)
        assertEquals(100, StatusCodec.parse(StatusCodec.encode(over))!!.batteryPercent)
        val under = StatusChannel.PeerStatus(online = false, batteryPercent = -5, storageFreeBytes = 0, storageTotalBytes = 0)
        assertEquals(0, StatusCodec.parse(StatusCodec.encode(under))!!.batteryPercent)
    }
}
