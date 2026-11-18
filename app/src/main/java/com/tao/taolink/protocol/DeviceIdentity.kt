package com.tao.tao.link.protocol

import java.util.UUID

/**
 * 本端设备身份：设备名 + 随机唯一标识（持久化，卸载重装随数据清零，指纹随重装变化是已知边界）。
 * 密钥类材料不落盘（SessionCrypto 契约）；唯一标识不是密钥，仅指纹口播材料。
 */
object DeviceIdentity {

    const val IDENTITY_FILE = "device_identity.txt"

    fun ownFingerprint(dataDir: java.io.File, deviceName: String): DeviceFingerprint {
        require(deviceName.isNotBlank()) { "设备名不可为空" }
        dataDir.mkdirs()
        val f = java.io.File(dataDir, IDENTITY_FILE)
        val uniqueId = if (f.isFile && f.readText().isNotBlank()) {
            f.readText()
        } else {
            UUID.randomUUID().toString().also { id ->
                val tmp = java.io.File(dataDir, "$IDENTITY_FILE.tmp")
                tmp.writeText(id)
                if (!tmp.renameTo(f)) throw IllegalStateException("设备身份原子写入失败")
            }
        }
        return DeviceFingerprint.of(deviceName, uniqueId)
    }
}
