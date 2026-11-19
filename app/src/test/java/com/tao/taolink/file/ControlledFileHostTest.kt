package com.tao.tao.link.file

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 被控端文件收宿主：建档/对账/校验/续传账目，纯 JVM 直测。 */
class ControlledFileHostTest {

    private val tmpDir: File = File.createTempFile("taolink-fh", "").let { it.delete(); it }
    private val inbox get() = tmpDir.resolve("inbox").apply { mkdirs() }

    private fun metaPayload(size: Long, sha: String, name: String = "f.bin", chunkSize: Int = 32): ByteArray = JSONObject()
        .put("name", name).put("sizeBytes", size).put("sha256", sha).put("chunkSize", chunkSize)
        .toString().toByteArray()

    private fun content(size: Int): ByteArray = ByteArray(size) { i -> ((i * 31 + 7) % 256).toByte() }

    private fun receiptJson(payload: ByteArray): JSONObject = JSONObject(String(payload))

    @Test
    fun `整册收齐校验通过且回执完整`() {
        val data = content(100)
        val host = ControlledFileHost(inbox)
        host.onFileMeta(metaPayload(data.size.toLong(), FileChunkCodec.sha256Hex(data)))
        val chunks = (0 until 4).map { idx ->
            val from = idx * 32
            val to = minOf(from + 32, data.size)
            Triple(idx, data.copyOfRange(from, to), idx == 3)
        }
        var lastReceipt: ByteArray? = null
        chunks.forEach { (idx, bytes, isLast) ->
            val r = host.onChunk(FileChunkCodec.encode("f.bin", idx, 4, bytes))
            if (isLast) lastReceipt = r
        }
        assertTrue(receiptJson(lastReceipt!!).getBoolean("complete"))
        assertEquals(FileChunkCodec.sha256Hex(data), FileReceiver.sha256Hex(inbox.resolve("f.bin")))
    }

    @Test
    fun `坏块哈希拒绝不入账且回执缺口`() {
        val data = content(100)
        val host = ControlledFileHost(inbox)
        host.onFileMeta(metaPayload(data.size.toLong(), FileChunkCodec.sha256Hex(data)))
        // 第 0 块发出后篡改数据：块哈希对账不过 → 收端拒收不入账，回执如实报缺口
        val good = FileChunkCodec.encode("f.bin", 0, 4, data.copyOfRange(0, 32))
        val tampered = FileChunkCodec.encode("f.bin", 0, 4, data.copyOfRange(0, 32))
        tampered[tampered.size - 1] = (tampered.last() + 1).toByte()
        val reject = receiptJson(host.onChunk(tampered)!!)
        assertFalse(reject.getBoolean("complete"))
        assertTrue(reject.getJSONArray("missing").let { arr -> (0 until arr.length()).any { arr.getInt(it) == 0 } })
        val ok = host.onChunk(good) // 正确块重发照单入账
        assertTrue(!receiptJson(ok!!).getBoolean("complete"))
        assertEquals(setOf(1, 2), receiptJson(host.onChunk(FileChunkCodec.encode("f.bin", 3, 4, data.copyOfRange(96, 100)))!!).let {
            it.getJSONArray("missing").let { arr -> (0 until arr.length()).map { arr.getInt(it) } }.toSet()
        })
    }

    @Test
    fun `整册哈希不过整件作废回执报错`() {
        val data = content(64)
        val host = ControlledFileHost(inbox)
        // 账面 sha 与真实内容不符（模拟发端账目错）
        host.onFileMeta(metaPayload(data.size.toLong(), "0".repeat(64)))
        host.onChunk(FileChunkCodec.encode("f.bin", 0, 2, data.copyOfRange(0, 32)))
        val r = host.onChunk(FileChunkCodec.encode("f.bin", 1, 2, data.copyOfRange(32, 64)))
        val json = receiptJson(r!!)
        assertFalse(json.getBoolean("complete"))
        assertEquals("sha_mismatch", json.optString("error"))
        // 作废后重收同档案可重来
        host.onFileMeta(metaPayload(data.size.toLong(), FileChunkCodec.sha256Hex(data)))
        host.onChunk(FileChunkCodec.encode("f.bin", 0, 2, data.copyOfRange(0, 32)))
        val r2 = host.onChunk(FileChunkCodec.encode("f.bin", 1, 2, data.copyOfRange(32, 64)))
        assertTrue(receiptJson(r2!!).getBoolean("complete"))
    }

    @Test
    fun `路径穿越文件名拒绝`() {
        val host = ControlledFileHost(inbox)
        assertNull(host.onFileMeta(metaPayload(4, "0".repeat(64), name = "../evil")))
        assertNull(host.onChunk(FileChunkCodec.encode("../evil", 0, 1, byteArrayOf(1))))
    }

    @Test
    fun `收件目录不存在建档自动创建并收齐校验`() {
        // 回归闸（run 44521321264 实锤）：真机 filesDir 下 inbox 目录不存在，建档 ENOENT、全块丢弃。
        // 单测基建此前的 mkdirs() 恰好掩盖了这条真实路径。
        val data = content(100)
        val bare = tmpDir.resolve("bare/inbox")
        assertFalse(bare.exists())
        val host = ControlledFileHost(bare)
        val metaReceipt = host.onFileMeta(metaPayload(data.size.toLong(), FileChunkCodec.sha256Hex(data)))
        assertTrue(bare.isDirectory)
        assertFalse(receiptJson(metaReceipt!!).getBoolean("complete"))
        var lastReceipt: ByteArray? = null
        (0 until 4).forEach { idx ->
            val from = idx * 32
            val to = minOf(from + 32, data.size)
            val r = host.onChunk(FileChunkCodec.encode("f.bin", idx, 4, data.copyOfRange(from, to)))
            if (idx == 3) lastReceipt = r
        }
        assertTrue(receiptJson(lastReceipt!!).getBoolean("complete"))
        assertEquals(FileChunkCodec.sha256Hex(data), FileReceiver.sha256Hex(bare.resolve("f.bin")))
    }

    @Test
    fun `未建档先收块丢弃`() {
        val host = ControlledFileHost(inbox)
        assertNull(host.onChunk(FileChunkCodec.encode("no-meta.bin", 0, 1, byteArrayOf(1, 2))))
    }

    @Test
    fun `重复元数据重开档案不脏账`() {
        val data = content(32)
        val host = ControlledFileHost(inbox)
        host.onFileMeta(metaPayload(data.size.toLong(), FileChunkCodec.sha256Hex(data)))
        host.onChunk(FileChunkCodec.encode("f.bin", 0, 1, data))
        // 收妥后对端重复发同一档案（重传场景）：档案已完册，回执直接完整
        val r = host.onChunk(FileChunkCodec.encode("f.bin", 0, 1, data))
        assertTrue(receiptJson(r!!).getBoolean("complete"))
    }
}
