package com.tao.tao.link.file

import com.tao.tao.link.LinkLog
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主控端文件发送（契约 #8 FileTransfer 主控侧实现）：
 * 发件箱目录 + 256KB 分块、逐块校验、发端整册 SHA-256、断点续传、进度回调、可取消。
 * 块走已加密会话（MasterSession.sendFileChunk）；收端哈希校验回执由对端实现（协议文档 §2-8）。
 */
class MasterFileSender(
    private val outboxDir: File,
    private val sendChunk: (payload: ByteArray) -> Long,
) : FileTransfer {

    private val sending = AtomicBoolean(false)

    fun resolveFile(meta: FileTransfer.Meta): File = File(outboxDir, meta.name)

    override fun send(meta: FileTransfer.Meta, chunkSizeBytes: Int, onProgress: (Int) -> Unit) {
        require(chunkSizeBytes > 0) { "分块大小必须为正" }
        if (!sending.compareAndSet(false, true)) {
            LinkLog.w(TAG, "已有发送任务进行中，拒绝并发发送")
            return
        }
        try {
            sendBlocking(meta, chunkSizeBytes, fromChunkIndex = 0, onProgress = onProgress)
        } finally {
            sending.set(false)
        }
    }

    override fun resume(meta: FileTransfer.Meta, fromChunkIndex: Int) {
        require(fromChunkIndex >= 0) { "续传起点不可为负" }
        if (!sending.compareAndSet(false, true)) {
            LinkLog.w(TAG, "已有发送任务进行中，拒绝并发续传")
            return
        }
        try {
            sendBlocking(meta, DEFAULT_CHUNK_SIZE, fromChunkIndex = fromChunkIndex, onProgress = {})
        } finally {
            sending.set(false)
        }
    }

    override fun cancel() {
        cancelled.set(true)
    }

    private val cancelled = AtomicBoolean(false)

    private fun sendBlocking(meta: FileTransfer.Meta, chunkSize: Int, fromChunkIndex: Int, onProgress: (Int) -> Unit) {
        cancelled.set(false)
        val file = resolveFile(meta)
        if (!file.isFile) {
            LinkLog.w(TAG, "发件不存在：${meta.name}（发件箱 $outboxDir）")
            return
        }
        val size = file.length()
        val totalChunks = totalChunks(size, chunkSize)
        if (fromChunkIndex > totalChunks) {
            LinkLog.w(TAG, "续传起点越界：$fromChunkIndex > $totalChunks")
            return
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(chunkSize)
            var index = 0
            while (index < totalChunks) {
                if (cancelled.get()) {
                    LinkLog.d(TAG, "发送取消：${meta.name} @ chunk $index")
                    return
                }
                val read = readChunk(stream, buf, index, totalChunks - 1)
                // 已读过的块参与整册哈希，保证发端哈希与真实文件一致
                if (index < fromChunkIndex) {
                    index++
                    continue
                }
                val chunk = buf.copyOf(read)
                digest.update(chunk)
                val chunkPayload = chunkPayload(meta, index, totalChunks, read, chunk)
                sendChunk(chunkPayload)
                onProgress((index + 1) * 100 / totalChunks)
                index++
            }
        }
        val fileHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (fileHash != meta.sha256) {
            LinkLog.w(TAG, "发端整册哈希与账不符，拒绝发送完成标记：$fileHash ≠ ${meta.sha256}")
            return
        }
        LinkLog.d(TAG, "发送完成：${meta.name} $totalChunks 块")
    }

    private fun chunkPayload(
        meta: FileTransfer.Meta,
        index: Int,
        total: Int,
        chunkLen: Int,
        chunk: ByteArray,
    ): ByteArray {
        val header = buildString {
            append("{\"name\":\"")
            append(escape(meta.name))
            append("\",\"index\":")
            append(index)
            append(",\"total\":")
            append(total)
            append(",\"chunkLen\":")
            append(chunkLen)
            append(",\"chunkSha256\":\"")
            append(MessageDigest.getInstance("SHA-256").digest(chunk).joinToString("") { "%02x".format(it) })
            append("\"}")
        }
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + headerBytes.size + chunk.size)
        out[0] = (headerBytes.size ushr 8).toByte()
        out[1] = headerBytes.size.toByte()
        headerBytes.copyInto(out, 2)
        chunk.copyInto(out, 2 + headerBytes.size)
        return out
    }

    private fun escape(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private fun readChunk(stream: java.io.InputStream, buf: ByteArray, index: Int, lastIndex: Int): Int {
        if (index < lastIndex) {
            var read = 0
            while (read < buf.size) {
                val n = stream.read(buf, read, buf.size - read)
                if (n < 0) return read
                read += n
            }
            return read
        }
        return stream.read(buf)
    }

    companion object {
        private const val TAG = "MasterFileSender"

        /** 契约默认分块 256KB（协议文档 §2-8）。 */
        const val DEFAULT_CHUNK_SIZE = 256 * 1024

        fun totalChunks(sizeBytes: Long, chunkSize: Int): Int {
            require(chunkSize > 0) { "分块大小必须为正" }
            if (sizeBytes <= 0) return 0
            return ((sizeBytes + chunkSize - 1) / chunkSize).toInt()
        }
    }
}
