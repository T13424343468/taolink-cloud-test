package com.tao.tao.link.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tao.tao.link.R
import com.tao.tao.link.protocol.DeviceIdentity
import com.tao.tao.link.protocol.PairingRecord
import com.tao.tao.link.protocol.PairingRecordStore
import com.tao.tao.link.protocol.PathType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主控主页（定稿原型 v2 s-home-master，老板 2026-11-18 拍板基线）：
 * 连接卡（附近发现/输码）+ 最近连过（读 PairingRecordStore 展示最后连接时间与路径类型，Y4 数据接口）+ 首次提示。
 * ui/ 只做壳与状态展示；发现与连接逻辑归 net/ 层（编码规范分层条款）。
 */
class MasterHomeActivity : AppCompatActivity() {

    private lateinit var store: PairingRecordStore
    private lateinit var recentList: LinearLayout
    private lateinit var recentEmpty: TextView
    private lateinit var firstTip: View
    private val dayFormat = SimpleDateFormat("M 月 d 日", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_master_home)
        store = PairingRecordStore(filesDir)
        recentList = findViewById(R.id.recent_list)
        recentEmpty = findViewById(R.id.recent_empty)
        firstTip = findViewById(R.id.first_tip)

        findViewById<Button>(R.id.btn_discover).setOnClickListener {
            startActivity(android.content.Intent(this, DiscoverActivity::class.java))
        }
        findViewById<Button>(R.id.btn_input_code).setOnClickListener {
            startActivity(android.content.Intent(this, PairInputActivity::class.java))
        }
        findViewById<Button>(R.id.btn_switch_role).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_tip_ok).setOnClickListener { firstTip.visibility = View.GONE }
        renderRecent()
    }

    private fun renderRecent() {
        val records = store.load()
            .filter { it.fingerprintHex != DeviceIdentity.ownFingerprint(filesDir, getString(R.string.default_device_name)).hex }
            .sortedByDescending { it.lastConnectedMillis }
        recentList.removeAllViews()
        recentEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        for (record in records.take(RECENT_LIMIT)) {
            recentList.addView(buildRecentRow(record))
        }
    }

    private fun buildRecentRow(record: PairingRecord): View {
        val row = layoutInflater.inflate(R.layout.item_recent_device, recentList, false)
        row.findViewById<TextView>(R.id.device_avatar).text = record.deviceName.take(1)
        row.findViewById<TextView>(R.id.device_name).text = record.deviceName
        row.findViewById<TextView>(R.id.device_meta).text =
            "${dayFormat.format(Date(record.lastConnectedMillis))} · ${pathLabel(record.pathType)}"
        row.findViewById<Button>(R.id.btn_connect_recent).setOnClickListener {
            // 重连免输码：凭已配对指纹走自愈重连通道（MasterSession.reconnect 归 net 层，本班界面壳接线见交活单）
            PairInputActivity.startForFingerprint(this, record)
        }
        return row
    }

    private fun pathLabel(pathType: PathType?): String = when (pathType) {
        PathType.LAN -> getString(R.string.path_lan)
        PathType.WAN_DIRECT -> getString(R.string.path_wan_direct)
        PathType.WAN_RELAY -> getString(R.string.path_wan_relay)
        null -> getString(R.string.path_unrecorded)
    }

    companion object {
        private const val RECENT_LIMIT = 5
    }
}
