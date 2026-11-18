package com.tao.tao.link.call

import org.json.JSONArray
import org.json.JSONObject

/**
 * 通话信令编解码（协议文档 §2-10，走 AUDIO 路由 + FrameType.CALL_SIGNAL）。
 * 三类信令：SDP offer/answer、ICE 候选；载荷 JSON，文本字符集 UTF-8。
 * 与既有会话帧同密（TLS + 会话密钥），本类只管序列化/反序列化，不做加密。
 */
object CallSignalCodec {

    private const val KIND_SDP = "sdp"
    private const val KIND_ICE = "ice"
    private const val KIND_HANGUP = "hangup"

    data class SdpSignal(val isOffer: Boolean, val sdp: String)
    data class IceSignal(val sdpMid: String, val sdpMLineIndex: Int, val candidate: String)

    fun encodeOffer(sdp: String): ByteArray =
        encodeSdp(offer = true, sdp = sdp)

    fun encodeAnswer(sdp: String): ByteArray =
        encodeSdp(offer = false, sdp = sdp)

    fun encodeCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String): ByteArray {
        val obj = JSONObject()
            .put("kind", KIND_ICE)
            .put("mid", sdpMid)
            .put("mline", sdpMLineIndex)
            .put("candidate", candidate)
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    fun encodeHangup(): ByteArray =
        JSONObject().put("kind", KIND_HANGUP).toString().toByteArray(Charsets.UTF_8)

    private fun encodeSdp(offer: Boolean, sdp: String): ByteArray =
        JSONObject()
            .put("kind", KIND_SDP)
            .put("offer", offer)
            .put("sdp", sdp)
            .toString()
            .toByteArray(Charsets.UTF_8)

    fun parseSdp(payload: ByteArray): SdpSignal? = try {
        val obj = JSONObject(String(payload, Charsets.UTF_8))
        if (obj.optString("kind") == KIND_SDP && obj.has("sdp")) {
            SdpSignal(isOffer = obj.optBoolean("offer"), sdp = obj.getString("sdp"))
        } else null
    } catch (_: Exception) { null }

    fun parseCandidate(payload: ByteArray): IceSignal? = try {
        val obj = JSONObject(String(payload, Charsets.UTF_8))
        if (obj.optString("kind") == KIND_ICE && obj.has("candidate")) {
            IceSignal(
                sdpMid = obj.optString("mid", "0"),
                sdpMLineIndex = obj.optInt("mline", 0),
                candidate = obj.getString("candidate"),
            )
        } else null
    } catch (_: Exception) { null }

    fun isHangup(payload: ByteArray): Boolean = try {
        JSONObject(String(payload, Charsets.UTF_8)).optString("kind") == KIND_HANGUP
    } catch (_: Exception) { false }
}
