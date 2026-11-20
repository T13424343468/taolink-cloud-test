package com.tao.tao.link.net

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log

/**
 * 主控端蓝牙扫信标（0.4.0 配对捎带信令）：mDNS 找不到时按厂商 ID 过滤扫被控机广播，
 * 解码出地址即回调 [onFound]。同一实例只允许一路扫描；扫失败静默停，交由调用方给提示。
 */
class BlePeerScanner(private val context: Context) {

    private var callback: ScanCallback? = null

    /** 开扫成功返回 true；无蓝牙/未开蓝牙/异常返回 false。重复调用幂等。 */
    fun start(onFound: (BleHandshake.BeaconInfo) -> Unit): Boolean {
        if (callback != null) return true
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return false
        val scanner = manager.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val payload = result.scanRecord
                    ?.getManufacturerSpecificData(BleHandshake.MANUFACTURER_ID) ?: return
                val info = BleHandshake.decode(payload) ?: return
                onFound(info)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
                stop()
            }
        }
        return try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(
                listOf(
                    ScanFilter.Builder()
                        .setManufacturerData(BleHandshake.MANUFACTURER_ID, ByteArray(0))
                        .build(),
                ),
                settings,
                cb,
            )
            callback = cb
            true
        } catch (e: Exception) {
            Log.w(TAG, "startScan failed", e)
            false
        }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
            // 停扫失败不拦调用方：回调已置空，后续不再收结果
        }
    }

    private companion object {
        const val TAG = "BlePeerScanner"
    }
}
