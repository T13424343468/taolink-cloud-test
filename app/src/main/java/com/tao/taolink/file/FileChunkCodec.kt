package com.tao.tao.link.file

import org.json.JSONObject
import java.security.MessageDigest

/**
 * 文件块线上格式唯一来源（协议文档 §2-8；两端共用，杜绝主被控各自拼装导致契约漂移——
 * 双机实弹 2026-11-19 九处跨端契约病的教训：格式各写各的，单测全绿装起来就死）：
 * 2 字节大端头长 + UTF-8 JSON 头（name/index/total/chunkLen/chunkSha256）+ 块数据。
 */
object FileChunkCodec {

    /** 解出的一个块：decode 不做校验，块级哈希对账由收端宿主判定（错块拒绝进账）。 */
    data class Chunk(
        val name: String,
        val index: Int,
        val total: Int,
        val chunkSha256: String,
        val data: ByteArray,
    )

    fun encode(name: String, index: Int, total: Int, data: ByteArray): ByteArray {
        val header = buildString {
            append("{\"name\":\"")
            append(escape(name))
            append("\",\"index\":")
            append(index)
            append(",\"total\":")
            append(total)
            append(",\"chunkLen\":")
            append(data.size)
            append(",\"chunkSha256\":\"")
            append(MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
            append("\"}")
        }
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + headerBytes.size + data.size)
        out[0] = (headerBytes.size ushr 8).toByte()
        out[1] = headerBytes.size.toByte()
        headerBytes.copyInto(out, 2)
        data.copyInto(out, 2 + headerBytes.size)
        return out
    }

    /** 头长/JSON/字段任一不合法返回 null，由收端按坏帧丢弃，不抛异常做控制流。 */
    fun decode(payload: ByteArray): Chunk? {
        if (payload.size < 2) return null
        val headerLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (headerLen <= 0 || 2 + headerLen > payload.size) return null
        return try {
            val obj = JSONObject(String(payload, 2, headerLen, Charsets.UTF_8))
            val name = obj.getString("name")
            if (name.isEmpty()) return null
            Chunk(
                name = name,
                index = obj.getInt("index"),
                total = obj.getInt("total"),
                chunkSha256 = obj.getString("chunkSha256"),
                data = payload.copyOfRange(2 + headerLen, payload.size),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun escape(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
