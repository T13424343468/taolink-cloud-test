package com.tao.tao.link.file

/** 文件互传契约（协议文档 §2-8）：走已加密会话；分块 256KB、逐块校验、断点续传位。 */
interface FileTransfer {
    data class Meta(val name: String, val sizeBytes: Long, val sha256: String)

    fun send(meta: Meta, chunkSizeBytes: Int = 256 * 1024, onProgress: (Int) -> Unit)

    fun resume(meta: Meta, fromChunkIndex: Int)

    fun cancel()
}
