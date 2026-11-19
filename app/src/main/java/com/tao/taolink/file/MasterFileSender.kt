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
                // 续传跳过的块也读到了，同样计入整册哈希（否则续传后哈希对不上，永远收不了班）
                digest.update(buf, 0, read)
                if (index < fromChunkIndex) {
                    index++
                    continue
                }
                val chunk = buf.copyOf(read)
                val chunkPayload = FileChunkCodec.encode(meta.name, index, totalChunks, chunk)
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
