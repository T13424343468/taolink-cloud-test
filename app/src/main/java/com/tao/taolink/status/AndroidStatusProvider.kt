package com.tao.tao.link.status

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.StatFs

/**
 * 本端状态采集（电量 + 存储），供 StatusChannel.snapshot 生成随心跳捎带载荷。
 * 在线判定由会话层传入；采集层只管电量与存储两个事实源。
 */
class AndroidStatusProvider(private val context: Context) {

    /** 采集快照；online 由调用方按会话实际连接态传入，不从本层猜。 */
    fun snapshot(online: Boolean): StatusChannel.PeerStatus {
        val batteryPercent = readBatteryPercent()
        val storage = readStorage()
        return StatusChannel.PeerStatus(
            online = online,
            batteryPercent = batteryPercent,
            storageFreeBytes = storage.first,
            storageTotalBytes = storage.second,
        )
    }

    private fun readBatteryPercent(): Int {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return 0
        return (level * 100 / scale).coerceIn(0, 100)
    }

    private fun readStorage(): Pair<Long, Long> {
        val dir = context.filesDir
        val stat = StatFs(dir.path)
        return stat.availableBytes to stat.totalBytes
    }
}
