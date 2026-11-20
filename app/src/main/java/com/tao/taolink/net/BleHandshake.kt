package com.tao.tao.link.net

/**
 * 蓝牙信令载荷（0.4.0 配对捎带信令，拍板口径：配对即信令、零服务器）：
 * 被控端把「本机地址+端口+指纹前缀」塞进蓝牙广播的厂商数据，主控端扫到即得门牌。
 * 载荷 = 魔数(1) + IPv4(4) + 端口(2, 大端) + 指纹前 4 字节(4)，共 11 字节，legacy 广播 31 字节内。
 * 指纹前缀仅作「是不是同一个人」的快速比对材料；配对码在连接握手仍全量校验，广播被仿冒也不放行。
 * 纯逻辑，可测。
 */
object BleHandshake {

    /** 厂商 ID：自定 0x544C（"TL"），仅本应用广播与过滤用，不冒充任何注册厂商。 */
    const val MANUFACTURER_ID = 0x544C

    private const val MAGIC = 0x54.toByte()
    private const val PAYLOAD_LEN = 11

    data class BeaconInfo(val ip: String, val port: Int, val fingerprintPrefixHex: String)

    /** 编码失败返回 null（非法 IP/端口/指纹），调用方静默不发，不让坏载荷上广播。 */
    fun encode(ip: String, port: Int, fingerprintHex: String): ByteArray? {
        val parts = ip.split(".").map { it.toIntOrNull() ?: return null }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return null
        if (port !in 1..65535) return null
        val fp = fingerprintHex.take(8)
        if (fp.length != 8 || fp.any { it !in "0123456789abcdefABCDEF" }) return null
        val out = ByteArray(PAYLOAD_LEN)
        out[0] = MAGIC
        for (i in 0..3) out[1 + i] = parts[i].toByte()
        out[5] = ((port shr 8) and 0xFF).toByte()
        out[6] = (port and 0xFF).toByte()
        for (i in 0..3) out[7 + i] = fp.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    /** 解码失败返回 null（长度/魔数不符），扫描侧静默丢弃，不进连接流程。 */
    fun decode(data: ByteArray): BeaconInfo? {
        if (data.size != PAYLOAD_LEN || data[0] != MAGIC) return null
        val ip = (1..4).joinToString(".") { (data[it].toInt() and 0xFF).toString() }
        val port = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
        val fp = (7..10).joinToString("") { "%02x".format(data[it]) }
        return BeaconInfo(ip, port, fp)
    }
}
