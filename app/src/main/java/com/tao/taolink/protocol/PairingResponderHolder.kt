package com.tao.tao.link.protocol

import java.io.File

/**
 * 进程内唯一 PairingResponder（双机实弹 2026-11-19 定因落码）：
 * 被控端「屏幕显示的码」与「服务校验的码」必须同一实例签发——
 * 界面页与驻留服务各持一个应答器时各签各的随机码，配对必拒。
 * 显示与校验同源后，配对码 TTL/一键重生成口径不变。
 */
object PairingResponderHolder {

    @Volatile
    private var responder: PairingResponder? = null

    fun get(filesDir: File, ownFingerprint: DeviceFingerprint): PairingResponder =
        responder ?: synchronized(this) {
            responder ?: PairingResponder(ownFingerprint, PairingRecordStore(filesDir)).also { responder = it }
        }
}
