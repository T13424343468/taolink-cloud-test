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

    // ---- 视频通话场景（视频引擎班补）：编解码与语音同通道，载荷按视频 SDP 复测 ----

    @Test
    fun videoOfferRoundtrip_mediaLineIntact() {
        // 视频通话 offer 含 m=video 媒体行：编解码不得截断/转义
        val videoSdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 VP8/90000"
        val parsed = CallSignalCodec.parseSdp(CallSignalCodec.encodeOffer(videoSdp))!!
        assertTrue(parsed.isOffer)
        assertEquals(videoSdp, parsed.sdp)
        assertTrue(parsed.sdp.contains("m=video"))
    }

    @Test
    fun videoAnswerRoundtrip_notOffer() {
        val videoSdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=recvonly"
        val parsed = CallSignalCodec.parseSdp(CallSignalCodec.encodeAnswer(videoSdp))!!
        assertFalse(parsed.isOffer)
        assertEquals(videoSdp, parsed.sdp)
    }

    @Test
    fun videoCandidateRoundtrip_videoMid() {
        // 视频 m 行的 ICE 候选（mid 指向第二媒体行）：往返字段原样
        val payload = CallSignalCodec.encodeCandidate("1", 1, "candidate:2 1 udp 2130706431 192.168.1.3 9001 typ host")
        val parsed = CallSignalCodec.parseCandidate(payload)!!
        assertEquals("1", parsed.sdpMid)
        assertEquals(1, parsed.sdpMLineIndex)
        assertEquals("candidate:2 1 udp 2130706431 192.168.1.3 9001 typ host", parsed.candidate)
    }

    @Test
    fun videoSdpNotMisreadAsHangup() {
        // 视频 offer 不会被误判为挂断（信令路由双引擎各自接账的前提）
        assertFalse(CallSignalCodec.isHangup(CallSignalCodec.encodeOffer("v=0\r\nm=video 9")))
    }
}
