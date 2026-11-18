package com.tao.tao.link.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSignalCodecTest {

    @Test
    fun offerRoundtrip_intact() {
        val payload = CallSignalCodec.encodeOffer("v=0\r\no=- 1 2 IN IP4 127.0.0.1")
        val parsed = CallSignalCodec.parseSdp(payload)!!
        assertTrue(parsed.isOffer)
        assertEquals("v=0\r\no=- 1 2 IN IP4 127.0.0.1", parsed.sdp)
    }

    @Test
    fun answerRoundtrip_intact() {
        val payload = CallSignalCodec.encodeAnswer("v=0 answer-body")
        val parsed = CallSignalCodec.parseSdp(payload)!!
        assertFalse(parsed.isOffer)
        assertEquals("v=0 answer-body", parsed.sdp)
    }

    @Test
    fun candidateRoundtrip_intact() {
        val payload = CallSignalCodec.encodeCandidate("0", 1, "candidate:1 1 udp 2130706431 192.168.1.2 8998 typ host")
        val parsed = CallSignalCodec.parseCandidate(payload)!!
        assertEquals("0", parsed.sdpMid)
        assertEquals(1, parsed.sdpMLineIndex)
        assertEquals("candidate:1 1 udp 2130706431 192.168.1.2 8998 typ host", parsed.candidate)
    }

    @Test
    fun parseSdp_garbagePayload_returnsNull() {
        assertNull(CallSignalCodec.parseSdp("not json".toByteArray()))
        assertNull(CallSignalCodec.parseSdp("{\"kind\":\"ice\"}".toByteArray()))
        assertNull(CallSignalCodec.parseSdp(ByteArray(0)))
    }

    @Test
    fun parseCandidate_garbagePayload_returnsNull() {
        assertNull(CallSignalCodec.parseCandidate("junk".toByteArray()))
        assertNull(CallSignalCodec.parseCandidate("{\"kind\":\"sdp\",\"sdp\":\"x\"}".toByteArray()))
    }

    @Test
    fun hangupFlag_andCrossTypeGuard() {
        assertTrue(CallSignalCodec.isHangup(CallSignalCodec.encodeHangup()))
        assertFalse(CallSignalCodec.isHangup(CallSignalCodec.encodeOffer("x")))
        assertFalse(CallSignalCodec.isHangup("garbage".toByteArray()))
        // sdp 载荷不会误判为 candidate
        assertNull(CallSignalCodec.parseCandidate(CallSignalCodec.encodeOffer("v=0")))
    }
}
