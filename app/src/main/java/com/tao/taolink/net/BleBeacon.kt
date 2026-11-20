package com.tao.tao.link.net

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log

/**
 * 被控端蓝牙信标（0.4.0 配对捎带信令）：配对码页常开时持续广播本机地址，
 * 主控端 mDNS 被路由器拦时扫蓝牙即得门牌，用户零操作（自动链条拍板口径）。
 * 无蓝牙硬件/未开蓝牙/权限缺失一律静默降级——蓝牙只是近场信令的增强路，不是唯一路。
 */
object BleBeacon {

    private const val TAG = "BleBeacon"

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: android.bluetooth.le.AdvertiseCallback? = null

    /** 开播成功返回 true；失败（无硬件/未开蓝牙/异常）返回 false，调用方不提示不重试。 */
    fun start(context: Context, ip: String, port: Int, fingerprintHex: String): Boolean {
        stop()
        val payload = BleHandshake.encode(ip, port, fingerprintHex) ?: return false
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return false
        val adv = manager.adapter?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser ?: return false
        return try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(false)
                .build()
            val data = AdvertiseData.Builder()
                .addManufacturerData(BleHandshake.MANUFACTURER_ID, payload)
                .setIncludeDeviceName(false)
                .build()
            val cb = object : android.bluetooth.le.AdvertiseCallback() {}
            adv.startAdvertising(settings, data, cb)
            advertiser = adv
            callback = cb
            Log.i(TAG, "beacon up: $ip:$port")
            true
        } catch (e: Exception) {
            Log.w(TAG, "startAdvertising failed", e)
            false
        }
    }

    fun stop() {
        val cb = callback
        callback = null
        try {
            advertiser?.stopAdvertising(cb)
        } catch (_: Exception) {
            // 停播失败不拦页面退出：系统侧广播随进程/适配器状态自愈
        }
        advertiser = null
    }
}
