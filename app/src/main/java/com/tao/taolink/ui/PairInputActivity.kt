package com.tao.tao.link.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.tao.tao.link.R
import com.tao.tao.link.net.WifiDirectPairing
import com.tao.tao.link.protocol.PairingRecord
import com.tao.tao.link.service.ControlledSessionService

/**
 * 输配对码（定稿原型 v2 s-pair-input）：6 位码提交给 MasterSession.pairAndConnect（net 层），
 * 成功进连接中态（ConnectingActivity），失败提示重输（s-pair-input 文案：不会连错人）。
 *
 * 0.5.0 拍板：发现与组网整层换 Wi-Fi Direct——系统自己发现设备、协商组网、分 IP，
 * 不再依赖同一 WiFi/热点/mDNS/蓝牙信令。提交后：扫到 1 台 TaoLink 设备直连；
 * 多台弹选择（不会连错人）；0 台进「正在找」态周期重扫（双方同时扫会互相看不见的时序坑），
 * 超时给明确文案。发现页带地址进来的预选目标仍保留（同一 WiFi 路径照旧）。
 */
class PairInputActivity : AppCompatActivity() {

    private var p2p: WifiDirectPairing? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingCode: String? = null
    private var pendingDeviceName: String = ""
    private var linked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair_input)
        val input = findViewById<EditText>(R.id.pair_code_input)
        findViewById<Button>(R.id.btn_connect_submit).setOnClickListener {
            val code = input.text?.toString().orEmpty()
            if (code.length != PAIR_CODE_LEN) {
                Toast.makeText(this, R.string.pair_input_aux, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 手填地址后路已删（0.4.2 拍板）；发现页带地址进来的预选目标仍保留。
            val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
            if (host.isNotEmpty()) {
                ConnectingActivity.start(
                    this,
                    host,
                    intent.getIntExtra(EXTRA_PORT, 0),
                    code,
                    intent.getStringExtra(EXTRA_FINGERPRINT).orEmpty(),
                    intent.getStringExtra(EXTRA_NAME).orEmpty(),
                )
            } else {
                submitWithP2pPeers(code)
            }
        }
        if (intent.getStringExtra(EXTRA_HOST).isNullOrEmpty()) {
            startP2pScan()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        p2p?.stop() // 离页即停扫与敲门监听，无残留回调（发现取消口径一致）
        p2p = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_P2P) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startP2pScan()
            } else {
                Toast.makeText(this, R.string.pair_input_p2p_permission, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 无预选目标：进页即扫（Wi-Fi Direct），提交那一刻以扫到的设备为准。 */
    private fun startP2pScan() {
        val missing = p2pPermissions()
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_P2P)
            return
        }
        if (p2p != null) return
        val controller = WifiDirectPairing(
            this,
            WifiDirectPairing.Role.MASTER,
            onPeersChanged = { devices -> runOnUiThread { onPeersChanged(devices) } },
            onLinked = { host -> runOnUiThread { onP2pLinked(host) } },
            onFailed = { reason -> runOnUiThread { onP2pFailed(reason) } },
        )
        p2p = controller
        controller.start()
    }

    private fun p2pPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOfNotNull(
                android.Manifest.permission.NEARBY_WIFI_DEVICES,
            ).filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        } else {
            listOfNotNull(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            ).filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        }

    private fun onPeersChanged(devices: List<WifiP2pDevice>) {
        // 提交后仍在等设备：一出现立即接上（用户零操作口径）。
        // 不按名字过滤（P2P 设备名是机型名，无 TaoLink 标识）：连错设备会在
        // 连接页快速失败给明确文案，协议层配对码+指纹校验兜底不会连错人。
        val code = pendingCode ?: return
        if (linked) return
        when {
            devices.isEmpty() -> Unit
            devices.size == 1 -> linkPeer(devices[0], code)
            else -> pickAndLink(devices, code)
        }
    }

    private fun submitWithP2pPeers(code: String) {
        val found = p2p?.peersSnapshot().orEmpty()
        when {
            found.isEmpty() -> waitAndRescan(code) // 0 台不劝退：进「正在找」态周期重扫
            found.size == 1 -> linkPeer(found[0], code)
            else -> pickAndLink(found, code)
        }
    }

    private fun pickAndLink(found: List<WifiP2pDevice>, code: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.pair_input_pick_peer)
            .setItems(found.map { it.deviceName }.toTypedArray()) { _, which ->
                linkPeer(found[which], code)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun linkPeer(device: WifiP2pDevice, code: String) {
        if (linked) return
        pendingCode = code
        pendingDeviceName = device.deviceName
        findViewById<TextView>(R.id.pair_input_status).text = getString(R.string.pair_input_p2p_linking)
        p2p?.connect(device)
    }

    /** 0 台：进「正在找」态，周期重扫救双方同时扫的时序坑，超时给明确文案。 */
    private fun waitAndRescan(code: String) {
        pendingCode = code
        findViewById<TextView>(R.id.pair_input_status).text = getString(R.string.pair_input_p2p_searching)
        var waited = 0L
        val tick = object : Runnable {
            override fun run() {
                if (linked) return
                if (waited >= P2P_WAIT_TIMEOUT_MS) {
                    findViewById<TextView>(R.id.pair_input_status).text =
                        getString(R.string.pair_input_p2p_none)
                    return
                }
                waited += P2P_RESCAN_INTERVAL_MS
                p2p?.rediscover()
                mainHandler.postDelayed(this, P2P_RESCAN_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(tick, P2P_RESCAN_INTERVAL_MS)
    }

    private fun onP2pLinked(host: String) {
        val code = pendingCode ?: return
        linked = true
        ConnectingActivity.start(
            this,
            host,
            ControlledSessionService.SERVICE_PORT,
            code,
            intent.getStringExtra(EXTRA_FINGERPRINT).orEmpty(),
            pendingDeviceName,
        )
    }

    private fun onP2pFailed(reason: String) {
        if (linked) return
        findViewById<TextView>(R.id.pair_input_status).text = getString(R.string.pair_input_p2p_failed)
    }

    companion object {
        private const val PAIR_CODE_LEN = 6
        private const val REQ_P2P = 4804
        private const val P2P_RESCAN_INTERVAL_MS = 4_000L
        private const val P2P_WAIT_TIMEOUT_MS = 20_000L
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_NAME = "name"

        fun startForPeer(context: Context, peer: com.tao.tao.link.net.DiscoveryService.DiscoveredPeer) {
            context.startActivity(
                Intent(context, PairInputActivity::class.java)
                    .putExtra(EXTRA_HOST, peer.host)
                    .putExtra(EXTRA_PORT, peer.port)
                    .putExtra(EXTRA_NAME, peer.deviceName),
            )
        }

        fun startForFingerprint(context: Context, record: PairingRecord) {
            context.startActivity(
                Intent(context, PairInputActivity::class.java)
                    .putExtra(EXTRA_FINGERPRINT, record.fingerprintHex)
                    .putExtra(EXTRA_NAME, record.deviceName),
            )
        }
    }
}
