package com.tao.tao.link.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.tao.tao.link.LinkLog

/**
 * 流畅档硬解码推流渲染（契约 #6 流畅档主控侧）：
 * H.264 Annex-B 载荷 → MediaCodec 硬解 → Surface 渲染。
 * 首个含 SPS/PPS 的载荷即配置解码器；输出直接送 Surface，不做像素搬运（性能帽口径）。
 * 解码器创建失败留痕并保持未建状态，画面回落省流档渲染路径（回退原因进日志，不静默）。
 */
class SmoothScreenDecoder(private val surface: Surface) {

    private var codec: MediaCodec? = null

    /** 解析并喂帧；payload 为完整 H.264 NAL 序列（可能含 SPS/PPS）。 */
    fun onFrame(payload: ByteArray) {
        if (payload.isEmpty()) return
        val csd = AnnexB.parameterSets(payload)
        if (csd != null && codec == null) configure(csd)
        val c = codec ?: return
        feed(c, payload)
    }

    private fun configure(csd: ByteArray) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, FALLBACK_W, FALLBACK_H)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        val csd0 = java.nio.ByteBuffer.allocate(csd.size).put(csd).apply { flip() }
        format.setByteBuffer("csd-0", csd0)
        val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            newCodec.configure(format, surface, null, 0)
            newCodec.start()
            codec = newCodec
        } catch (e: Exception) {
            LinkLog.w(TAG, "硬解码配置失败，回落省流档：${e.message}")
            newCodec.release()
            codec = null
        }
    }

    private fun feed(codec: MediaCodec, payload: ByteArray) {
        var offset = 0
        while (offset < payload.size) {
            val inBuf = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inBuf < 0) break
            val buf = codec.getInputBuffer(inBuf) ?: break
            val len = minOf(buf.capacity(), payload.size - offset)
            buf.clear()
            buf.put(payload, offset, len)
            codec.queueInputBuffer(inBuf, 0, len, 0, 0)
            offset += len
            drain(codec)
        }
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (out < 0) break
            // 渲染直接送 Surface（releaseOutputBuffer render=true），不做像素搬运
            codec.releaseOutputBuffer(out, true)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }

    fun release() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            LinkLog.w(TAG, "解码器释放异常：${e.message}")
        }
        codec = null
    }

    companion object {
        private const val TAG = "SmoothDecoder"
        private const val TIMEOUT_US = 10_000L
        private const val FALLBACK_W = 1280
        private const val FALLBACK_H = 720
        private const val MAX_INPUT_SIZE = 1 shl 20
    }
}
