package com.tao.tao.link.protocol

import java.security.MessageDigest

/**
 * 设备指纹（协议文档 §2-2：配对成功双方交换设备指纹并落配对记录，重连免输码）。
 * 指纹 = 对端口播身份 + 随机标识的 SHA-256；纯逻辑，可测。
 */
data class DeviceFingerprint(val hex: String, val deviceName: String) {

    companion object {
        /** 按对端口播内容构造指纹：身份材料 = 设备名 + 唯一标识（Build 系或随机生成 UUID）。 */
        fun of(deviceName: String, uniqueId: String): DeviceFingerprint {
            require(deviceName.isNotBlank()) { "设备名不可为空" }
            require(uniqueId.isNotBlank()) { "唯一标识不可为空" }
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest("$deviceName|$uniqueId".toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return DeviceFingerprint(hex, deviceName)
        }
    }
}
