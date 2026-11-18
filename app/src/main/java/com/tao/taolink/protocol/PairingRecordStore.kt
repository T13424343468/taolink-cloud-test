package com.tao.tao.link.protocol

import java.io.File

/**
 * 配对记录落盘（协议文档 §2-2：配对成功落配对记录，重连免输码）。
 * 账目行格式（一行一条，Tab 分列）：
 * 旧账 3 列：指纹 hex\t设备名\t配对时间毫秒；
 * 新账 5 列（拍板 2026-11-18 12:27 Y4）：指纹 hex\t设备名\t配对时间毫秒\t最后连接时间毫秒\t路径类型 tag；
 * 读账双向兼容，旧 3 列账最后连接时间回退配对时间、路径按未记录处理；
 * 写盘一律 tmp+move 原子替换（编码规范三.6 全线家法）。
 */
class PairingRecordStore(private val dir: File) {

    private val recordFile: File get() = File(dir, FILE_NAME)

    /** 全量读取已配对指纹；文件不存在或损坏视为空账（首启零记录是正常空态）。 */
    fun load(): List<PairingRecord> {
        val f = recordFile
        if (!f.isFile) return emptyList()
        return f.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3 && parts.size != 5) return@mapNotNull null
                val pairedAt = parts[2].toLongOrNull() ?: return@mapNotNull null
                if (parts.size == 3) return@mapNotNull PairingRecord(parts[0], parts[1], pairedAt)
                val lastConnected = parts[3].toLongOrNull() ?: return@mapNotNull null
                PairingRecord(parts[0], parts[1], pairedAt, lastConnected, PathType.fromTag(parts[4]))
            }
    }

    fun contains(fingerprintHex: String): Boolean =
        load().any { it.fingerprintHex == fingerprintHex }

    /** 已配对则保留原记录（重连免输码），新指纹追加；写盘原子替换。 */
    fun upsert(record: PairingRecord) {
        require(record.fingerprintHex.isNotBlank()) { "指纹不可为空" }
        require(record.deviceName.isNotBlank()) { "设备名不可为空" }
        writeRecords(listOf(record) + load().filterNot { it.fingerprintHex == record.fingerprintHex })
    }

    /**
     * 连接成立即回填最后连接时间与路径类型（Y4 拍板：主页「最近连过」依据）。
     * 指纹不在账上返回 false，不隐式建账（建账只走配对，口径不混）。
     */
    fun markConnected(fingerprintHex: String, pathType: PathType, atMillis: Long): Boolean {
        val records = load()
        val target = records.firstOrNull { it.fingerprintHex == fingerprintHex } ?: return false
        val updated = target.copy(lastConnectedMillis = atMillis, pathType = pathType)
        writeRecords(listOf(updated) + records.filterNot { it.fingerprintHex == fingerprintHex })
        return true
    }

    private fun writeRecords(ordered: List<PairingRecord>) {
        dir.mkdirs()
        val lines = ordered.joinToString("\n") {
            "${it.fingerprintHex}\t${it.deviceName}\t${it.pairedAtMillis}" +
                "\t${it.lastConnectedMillis}\t${it.pathType?.tag ?: TAG_NONE}"
        }
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(lines)
        if (!tmp.renameTo(recordFile)) {
            // 同分区 rename 失败按异常报，不静默吞（编码规范三.2）
            throw IllegalStateException("配对记录原子替换失败: ${recordFile.path}")
        }
    }

    companion object {
        const val FILE_NAME = "pairing_records.txt"

        /** 路径未记录占位（旧账升级 / 无路径信息时写此值，读账还原为 null）。 */
        const val TAG_NONE = "none"
    }
}

data class PairingRecord(
    val fingerprintHex: String,
    val deviceName: String,
    val pairedAtMillis: Long,
    /** 最后连接时间（Y4）；缺省=配对即首连口径。 */
    val lastConnectedMillis: Long = pairedAtMillis,
    /** 路径类型（Y4）：局域网/外网直连/中继；null=尚未记录。 */
    val pathType: PathType? = null,
)
