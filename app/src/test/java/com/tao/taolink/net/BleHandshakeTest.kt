package com.tao.tao.link.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 蓝牙信令载荷编解码回归闸（0.4.0 配对捎带信令）：编解码往返一致，坏输入一律拒之门外。 */
class BleHandshakeTest {

    private val fp = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

    @Test
    fun `encode decode roundtrip keeps ip port and fingerprint prefix`() {
        val payload = BleHandshake.encode("192.168.1.77", 48190, fp)!!
        assertEquals(11, payload.size)
        val info = BleHandshake.decode(payload)!!
        assertEquals("192.168.1.77", info.ip)
        assertEquals(48190, info.port)
        assertEquals(fp.take(8), info.fingerprintPrefixHex)
    }

    @Test
    fun `decode rejects wrong length and wrong magic`() {
        assertNull(BleHandshake.decode(ByteArray(10)))
        assertNull(BleHandshake.decode(ByteArray(12)))
        val payload = BleHandshake.encode("10.0.0.1", 80, fp)!!
        payload[0] = 0x00
        assertNull(BleHandshake.decode(payload))
    }

    @Test
    fun `encode rejects invalid ip port and short fingerprint`() {
        assertNull(BleHandshake.encode("999.1.1.1", 80, fp))
        assertNull(BleHandshake.encode("192.168.1", 80, fp))
        assertNull(BleHandshake.encode("192.168.1.1", 0, fp))
        assertNull(BleHandshake.encode("192.168.1.1", 70000, fp))
        assertNull(BleHandshake.encode("192.168.1.1", 80, "a1b2"))
        assertNull(BleHandshake.encode("192.168.1.1", 80, ""))
    }

    @Test
    fun `port big endian survives 0 and 255 low bytes`() {
        for (port in listOf(1, 3478, 48190, 65535)) {
            val info = BleHandshake.decode(BleHandshake.encode("1.2.3.4", port, fp)!!)!!
            assertEquals(port, info.port)
        }
        assertTrue(BleHandshake.decode(BleHandshake.encode("1.2.3.4", 65535, fp)!!)!!.port == 65535)
    }
}
