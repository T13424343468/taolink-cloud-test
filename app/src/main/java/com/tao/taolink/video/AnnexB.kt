package com.tao.tao.link.video

/**
 * H.264 Annex-B 流解析（流畅档：硬解码推流渲染的前置）。
 * 从画面载荷中切出 NAL 单元并定位 SPS/PPS 参数集，供 MediaCodec 配置 csd。
 * 纯逻辑可测；帧格式为契约 #6 实现自声明（协议文档 §2-6 二进制透传口径）。
 */
object AnnexB {

    private val START_CODE_4 = byteArrayOf(0, 0, 0, 1)
    private val START_CODE_3 = byteArrayOf(0, 0, 1)

    data class Nal(val startIndex: Int, val payloadStart: Int, val endExclusive: Int) {
        fun type(): Int = 0x1F
        fun typeId(data: ByteArray): Int = data[payloadStart].toInt() and 0x1F
    }

    fun isNalSps(typeId: Int) = typeId == SPS_TYPE
    fun isNalPps(typeId: Int) = typeId == PPS_TYPE
    fun isNalIdr(typeId: Int) = typeId == IDR_TYPE

    const val SPS_TYPE = 7
    const val PPS_TYPE = 8
    const val IDR_TYPE = 5

    /** 切出全部 NAL（含起始码在内）；无完整 NAL 返回空表。 */
    fun nalUnits(data: ByteArray): List<Nal> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i < data.size) {
            if (startsWith(data, i, START_CODE_4)) {
                starts.add(i)
                i += 4
            } else if (startsWith(data, i, START_CODE_3)) {
                starts.add(i)
                i += 3
            } else {
                i++
            }
        }
        return starts.mapIndexed { idx, start ->
            val end = if (idx + 1 < starts.size) starts[idx + 1] else data.size
            Nal(start, start + (if (startsWith(data, start, START_CODE_4)) 4 else 3), end)
        }
    }

    /** 提取参数集（SPS+PPS，带起始码）作为 csd；不齐返回 null。 */
    fun parameterSets(data: ByteArray): ByteArray? {
        val nals = nalUnits(data)
        val sps = nals.firstOrNull { isNalSps(it.typeId(data)) } ?: return null
        val pps = nals.firstOrNull { isNalPps(it.typeId(data)) } ?: return null
        return data.copyOfRange(sps.startIndex, pps.endExclusive)
    }

    /** 是否含即时刷新帧（IDR）：硬解码器需从 IDR 起播。 */
    fun hasIdr(data: ByteArray): Boolean =
        nalUnits(data).any { isNalIdr(it.typeId(data)) }

    private fun startsWith(data: ByteArray, offset: Int, prefix: ByteArray): Boolean {
        if (offset + prefix.size > data.size) return false
        for (j in prefix.indices) {
            if (data[offset + j] != prefix[j]) return false
        }
        return true
    }
}
