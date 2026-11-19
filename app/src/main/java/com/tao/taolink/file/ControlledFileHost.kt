package com.tao.tao.link.file

import com.tao.tao.link.LinkLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 被控端文件收宿主（协议文档 §2-8 收端实现）：
 * 收元数据建档 → 收块写入 FileReceiver（逐块哈希对账）→ 收齐即整册校验，
 * 校验过与不过都回执（JSON），主控端据此续传或收班。
 * 纯 JVM（无 Android 依赖），单测可直测；线程约定：仅会话帧处理单线程进出。
 */
class ControlledFileHost(private val inboxDir: File) {

    private val receivers = HashMap<String, FileReceiver>()

    /** 已完整收妥并校验通过的文件名（重复块幂等，不再重开档案）。 */
    private val completed = HashSet<String>()

    /** 收到文件元数据：建档。回执载荷（立即回给主控端）或 null（坏元数据，无回执）。 */
    fun onFileMeta(payload: ByteArray): ByteArray? {
        val meta = parseMeta(payload) ?: run {
            LinkLog.w(TAG, "文件元数据解析失败，丢弃")
            return null
        }
        if (!isNameValid(meta.name)) {
            LinkLog.w(TAG, "文件名非法，拒绝：${meta.name}")
            return null
        }
        if (meta.name in completed) return receipt(meta.name, complete = true, missing = emptyList())
        receivers.remove(meta.name)?.cancel()
        // 首次建档即确保收件目录存在（run 44521321264 实锤：真机 filesDir 下无 inbox 目录，
        // 建档 ENOENT 失败、后续块全部"未收到元数据"丢弃——单测 mkdirs 掩盖了此路径）
        if (!inboxDir.isDirectory && !inboxDir.mkdirs()) {
            LinkLog.w(TAG, "收件目录创建失败：${inboxDir.absolutePath}")
            return null
        }
        val receiver = try {
            FileReceiver(
                targetFile = File(inboxDir, meta.name),
                sidecarFile = File(inboxDir, meta.name + SIDECAR_SUFFIX),
                meta = FileTransfer.Meta(meta.name, meta.sizeBytes, meta.sha256),
                chunkSizeBytes = meta.chunkSize,
            )
        } catch (e: Exception) {
            LinkLog.w(TAG, "建档失败 ${meta.name}：${e.message}")
            return null
        }
        receivers[meta.name] = receiver
        LinkLog.d(TAG, "文件接收开始 ${meta.name} ${meta.sizeBytes}字节")
        return receipt(meta.name, complete = false, missing = (0 until totalChunksOf(meta)).toList())
    }

    /** 收到一块：对账写账。回执载荷，坏帧/无档案返回 null。 */
    fun onChunk(payload: ByteArray): ByteArray? {
        val chunk = FileChunkCodec.decode(payload) ?: run {
            LinkLog.w(TAG, "文件块解析失败，丢弃")
            return null
        }
        if (!isNameValid(chunk.name)) {
            LinkLog.w(TAG, "文件名非法，拒绝：${chunk.name}")
            return null
        }
        if (chunk.name in completed) return receipt(chunk.name, complete = true, missing = emptyList())
        val receiver = receivers[chunk.name] ?: run {
            LinkLog.w(TAG, "未收到 ${chunk.name} 元数据，块丢弃 index=${chunk.index}")
            return null
        }
        val chunkSha = FileChunkCodec.sha256Hex(chunk.data)
        if (!chunkSha.equals(chunk.chunkSha256, ignoreCase = true)) {
            LinkLog.w(TAG, "块哈希对账不过 ${chunk.name}#${chunk.index}，丢弃待重发")
            return receipt(chunk.name, complete = false, missing = receiver.missingChunks().toList())
        }
        try {
            receiver.writeChunk(chunk.index, chunk.data)
        } catch (e: Exception) {
            LinkLog.w(TAG, "块写入失败 ${chunk.name}#${chunk.index}：${e.message}")
            return null
        }
        LinkLog.d(TAG, "块入账 ${chunk.name}#${chunk.index}（${receiver.receivedChunks().size}/${chunk.total}）")
        if (receiver.isComplete()) {
            return finishAndReceipt(chunk.name, receiver)
        }
        if (chunk.index == chunk.total - 1) {
            // 末块已到但账未齐：如实回执缺口，主控端据此续传，不装作收完
            return receipt(chunk.name, complete = false, missing = receiver.missingChunks().toList())
        }
        return receipt(chunk.name, complete = false, missing = emptyList())
    }

    /** 会话拆线时收尾：未完成档案保留侧车（断点续传位），进程内档案注销。 */
    fun close() {
        receivers.values.forEach { it.close() }
        receivers.clear()
    }

    private fun finishAndReceipt(name: String, receiver: FileReceiver): ByteArray {
        return try {
            receiver.verifyAndFinish()
            completed.add(name)
            receivers.remove(name)
            LinkLog.d(TAG, "文件接收完成 校验通过 $name")
            receipt(name, complete = true, missing = emptyList())
        } catch (e: IllegalStateException) {
            LinkLog.w(TAG, "文件接收完成 校验失败 $name：${e.message}")
            receiver.cancel()
            receivers.remove(name)
            receipt(name, complete = false, missing = emptyList(), error = "sha_mismatch")
        }
    }

    private fun parseMeta(payload: ByteArray): Meta? = try {
        val obj = JSONObject(String(payload, Charsets.UTF_8))
        val name = obj.getString("name")
        Meta(
            name = name,
            sizeBytes = obj.getLong("sizeBytes"),
            sha256 = obj.getString("sha256"),
            chunkSize = obj.optInt("chunkSize", FileReceiver.DEFAULT_CHUNK),
        )
    } catch (e: Exception) {
        null
    }

    private fun totalChunksOf(meta: Meta): Int {
        val size = meta.sizeBytes
        if (size <= 0) return 1
        return (((size - 1) / meta.chunkSize) + 1).toInt()
    }

    private fun receipt(name: String, complete: Boolean, missing: List<Int>, error: String? = null): ByteArray {
        val obj = JSONObject()
        obj.put("name", name)
        obj.put("complete", complete)
        val missingArr = JSONArray()
        missing.take(MAX_MISSING_REPORT).forEach { missingArr.put(it) }
        obj.put("missing", missingArr)
        if (error != null) obj.put("error", error)
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    private fun isNameValid(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\')

    data class Meta(val name: String, val sizeBytes: Long, val sha256: String, val chunkSize: Int)

    companion object {
        private const val TAG = "ControlledFileHost"
        private const val SIDECAR_SUFFIX = ".progress"
        private const val MAX_MISSING_REPORT = 64

        const val META_TYPE = "FILE_META"
    }
}
