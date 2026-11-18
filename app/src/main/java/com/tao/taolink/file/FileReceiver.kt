package com.tao.tao.link.file

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 文件收端实现（协议文档 §2-8 + FileTransfer 骨架契约口径）：
 * 256KB 分块、按块偏移写入预分配文件、断点续传（进度侧车文件落盘，tmp+move 原子替换）、
 * 收完整文件哈希校验，校验不过整件作废重收。纯 JVM 逻辑，可测。
 */
class FileReceiver(
    private val targetFile: File,
    private val sidecarFile: File,
    private val meta: FileTransfer.Meta,
    chunkSizeBytes: Int = DEFAULT_CHUNK,
) {
    private val chunkSize = chunkSizeBytes.coerceAtLeast(MIN_CHUNK)
    private val totalChunks: Int = totalChunksOf(meta.sizeBytes, chunkSize)
    private val received = sortedSetOf<Int>()
    private val randomAccess: RandomAccessFile = RandomAccessFile(targetFile, "rw")
    private var closed = false

    init {
        require(meta.sizeBytes >= 0) { "文件大小不可为负" }
        require(meta.sha256.isNotBlank()) { "哈希不可为空" }
        restoreFromSidecar()
        // 预分配整文件大小，块按偏移写入，中间断线不产生空洞文件错位
        if (randomAccess.length() < meta.sizeBytes) {
            randomAccess.setLength(meta.sizeBytes)
        }
    }

    /** 收到一块：写入对应偏移并记账。重复块幂等（重发不重复记账，协议 §2-5 不重放口径）。 */
    fun writeChunk(index: Int, data: ByteArray) {
        check(!closed) { "接收已关闭" }
        if (index < 0 || index >= totalChunks) throw IndexOutOfBoundsException("块序号越界: $index")
        if (index in received) return
        if (data.isEmpty()) throw IllegalArgumentException("空块拒绝: $index")
        randomAccess.seek(index.toLong() * chunkSize)
        randomAccess.write(data)
        received.add(index)
        persistSidecar()
    }

    /** 已收块索引；断点续传时发送方从 missingChunks 补发。 */
    fun receivedChunks(): Set<Int> = received.toSet()

    fun missingChunks(): Set<Int> = (0 until totalChunks).filterTo(mutableSetOf()) { it !in received }

    fun isComplete(): Boolean = received.size == totalChunks

    /**
     * 收齐后整件哈希校验；对上返回 true 并清侧车，对不上抛 IllegalStateException（整件作废重收，
     * 不部分交付——协议 §2-8 校验口径）。
     */
    fun verifyAndFinish(): Boolean {
        check(isComplete()) { "尚缺 ${missingChunks().size} 块，不进入校验" }
        val actual = sha256Hex(targetFile)
        if (!actual.equals(meta.sha256, ignoreCase = true)) {
            throw IllegalStateException("哈希校验不过，整件作废重收")
        }
        sidecarFile.delete()
        close()
        return true
    }

    fun cancel() {
        close()
        targetFile.delete()
        sidecarFile.delete()
    }

    fun close() {
        if (closed) return
        closed = true
        randomAccess.close()
    }

    private fun persistSidecar() {
        val text = received.joinToString(",") { it.toString() }
        val tmp = File(sidecarFile.parentFile, sidecarFile.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(sidecarFile)) {
            throw IllegalStateException("侧车文件原子替换失败: ${sidecarFile.path}")
        }
    }

    private fun restoreFromSidecar() {
        if (!sidecarFile.isFile) return
        val text = sidecarFile.readText().trim()
        if (text.isEmpty()) return
        text.split(",").forEach { idx ->
            idx.toIntOrNull()?.let { if (it in 0 until totalChunks) received.add(it) }
        }
    }

    companion object {
        const val DEFAULT_CHUNK: Int = 256 * 1024
        private const val MIN_CHUNK: Int = 1

        fun totalChunksOf(sizeBytes: Long, chunkSize: Int): Int {
            require(chunkSize > 0) { "分块必须为正" }
            if (sizeBytes == 0L) return 1
            return (((sizeBytes - 1) / chunkSize) + 1).toInt()
        }

        fun sha256Hex(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
