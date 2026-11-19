package com.tao.tao.link.file

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 文件块线上格式：两端共用同一编解码器，格式漂移在此拦（九处跨端契约病的教训）。 */
class FileChunkCodecTest {

    @Test
    fun `编解码往返字段一致`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val payload = FileChunkCodec.encode("report.txt", 3, 7, data)
        val chunk = FileChunkCodec.decode(payload)!!
        assertEquals("report.txt", chunk.name)
        assertEquals(3, chunk.index)
        assertEquals(7, chunk.total)
        assertArrayEquals(data, chunk.data)
        assertEquals(FileChunkCodec.sha256Hex(data), chunk.chunkSha256)
    }

    @Test
    fun `文件名带引号反斜杠不破格式`() {
        val payload = FileChunkCodec.encode("a\"b\\c.txt", 0, 1, byteArrayOf(9))
        val chunk = FileChunkCodec.decode(payload)!!
        assertEquals("a\"b\\c.txt", chunk.name)
    }

    @Test
    fun `坏帧不抛异常返回null`() {
        assertNull(FileChunkCodec.decode(ByteArray(0)))
        assertNull(FileChunkCodec.decode(byteArrayOf(0)))
        assertNull(FileChunkCodec.decode(byteArrayOf(0, 100))) // 声明头长 100 但无数据
        val bad = byteArrayOf(0, 4) + "junk".toByteArray() + byteArrayOf(1)
        assertNull(FileChunkCodec.decode(bad)) // 头不是合法 JSON
    }

    @Test
    fun `头长合法但载荷截断仍可解出短块`() {
        // 头合法、数据区为空：decode 应解出空数据块（空块拒绝是收端宿主的职责）
        val payload = FileChunkCodec.encode("x", 0, 1, ByteArray(0))
        val chunk = FileChunkCodec.decode(payload)!!
        assertEquals(0, chunk.data.size)
        assertEquals(0, chunk.index)
    }
}
