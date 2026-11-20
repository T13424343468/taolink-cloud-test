package com.tao.tao.link.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.tao.tao.link.net.BleHandshake
import com.tao.tao.link.net.BlePeerScanner
import com.tao.tao.link.net.DiscoveryService
import com.tao.tao.link.net.NsdDiscoveryController
import com.tao.tao.link.protocol.PairingRecord
import com.tao.tao.link.service.ControlledSessionService

/**
 * 输配对码（定稿原型 v2 s-pair-input）：6 位码提交给 MasterSession.pairAndConnect（net 层），
 * 成功进连接中态（ConnectingActivity），失败提示重输（s-pair-input 文案：不会连错人）。
 *
 * 真机修红（0.2.5 验收：输码连不上）：无发现入口（主控主页直进/重连记录）进来时本页自己扫——
 * 提交时按当前扫到的可被控设备走：0 台给明确提示；1 台直连；多台弹选择（不会连错人）。
 * 此前空地址直接开连接必败，等于整条「输码」路是死的。
 */
class PairInputActivity : AppCompatActivity() {

    private var discovery: NsdDiscoveryController? = null
    private val peers = LinkedHashMap<String, DiscoveryService.DiscoveredPeer>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // 蓝牙信令兜底（0.4.0 配对捎带信令）：mDNS 0 台时自动转蓝牙扫地址，用户零操作
    private var bleScanner: BlePeerScanner? = null
    private var bleConnected = false
    private var pendingBleCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair_input)
        // 自身修红（0.2.6 真机复验暴露）：discovery 原为 lateinit，仅无预选地址时初始化——
        // 从发现页带地址进本页，离开页面 onDestroy 解引用必崩，整条「找到设备→输码」路带雷。
        // 改为进页必初始化（组播锁随构造持有无害），仅自扫描分支起浏览。
        discovery = NsdDiscoveryController(this)
        val input = findViewById<EditText>(R.id.pair_code_input)
        val ipInput = findViewById<EditText>(R.id.pair_input_ip)
        findViewById<Button>(R.id.btn_connect_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_connect_submit).setOnClickListener {
            val code = input.text?.toString().orEmpty()
            if (code.length != PAIR_CODE_LEN) {
                Toast.makeText(this, R.string.pair_input_aux, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val manualIp = ipInput.text?.toString().orEmpty().trim()
            if (manualIp.isNotEmpty()) {
                // 直连后路（发现被 OEM/路由器拦时的兜底路径，§6 换）：输对方屏幕显示的本机地址直达，
                // 端口即被控监听端口；指纹留空同发现路径口径，配对码仍校验不连错人。
                ConnectingActivity.start(
                    this,
                    manualIp,
                    ControlledSessionService.SERVICE_PORT,
                    code,
                    intent.getStringExtra(EXTRA_FINGERPRINT).orEmpty(),
                    intent.getStringExtra(EXTRA_NAME).orEmpty(),
                )
                return@setOnClickListener
            }
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
                submitWithScannedPeer(code)
            }
        }
        if (intent.getStringExtra(EXTRA_HOST).isNullOrEmpty()) {
            startSelfScan()
        }
    }

    override fun onDestroy() {
        discovery?.stopBrowsing() // 离页即停扫，无残留回调（发现取消口径一致）
        bleScanner?.stop()
        bleScanner = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE_SCAN) {
            val code = pendingBleCode
            pendingBleCode = null
            if (code != null && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startBleFallback(code)
            } else {
                Toast.makeText(this, R.string.pair_input_need_peer, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 无预选目标：进页即扫（同发现页口径），提交那一刻以扫到的设备为准。 */
    private fun startSelfScan() {
        val controller = discovery ?: return
        controller.startBrowsing(
            onFound = { peer -> runOnUiThread { if (peer.supportsControlled) peers[peer.host] = peer } },
            onError = { /* 扫描启动失败不拦提交：提交时按 0 台口径给提示 */ },
        )
    }

    private fun submitWithScannedPeer(code: String) {
        val found = peers.values.toList()
        when {
            found.isEmpty() -> startBleFallback(code) // 0 台不直接劝退：自动转蓝牙信令兜底（0.4.0 拍板口径）
            found.size == 1 -> connectToPeer(found[0], code)
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.pair_input_pick_peer)
                .setItems(found.map { it.deviceName }.toTypedArray()) { _, which ->
                    connectToPeer(found[which], code)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * 蓝牙信令兜底（0.4.0）：被控机配对码页在广播本机地址，这里按厂商 ID 过滤扫，
     * 扫到即带码直连；超时未扫到给热点后路提示。权限缺失先申请，回来续上同一码。
     */
    private fun startBleFallback(code: String) {
        val missing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOfNotNull(
                android.Manifest.permission.BLUETOOTH_SCAN,
            ).filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        } else {
            listOfNotNull(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            ).filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        }
        if (missing.isNotEmpty()) {
            pendingBleCode = code
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_BLE_SCAN)
            return
        }
        val scanner = bleScanner ?: BlePeerScanner(this).also { bleScanner = it }
        val started = scanner.start { info -> runOnUiThread { connectToBlePeer(info, code) } }
        if (!started) {
            // 蓝牙不可用（未开/无硬件）：退回原提示口径，不拦用户走地址后路
            Toast.makeText(this, R.string.pair_input_need_peer, Toast.LENGTH_LONG).show()
            return
        }
        findViewById<TextView>(R.id.pair_input_status).text = getString(R.string.pair_input_ble_searching)
        mainHandler.postDelayed({
            if (!bleConnected) {
                scanner.stop()
                findViewById<TextView>(R.id.pair_input_status).text = getString(R.string.pair_input_ble_timeout)
            }
        }, BLE_TIMEOUT_MS)
    }

    private fun connectToBlePeer(info: BleHandshake.BeaconInfo, code: String) {
        if (bleConnected) return
        bleConnected = true
        bleScanner?.stop()
        val statusView = findViewById<TextView>(R.id.pair_input_status)
        // 先探可达再进连接（0.4.1 修红）：蓝牙只递门牌，TCP 仍要网络路——
        // 双方都不在同一网（如都关 WiFi 走蜂窝）时门牌指不到，直接进连接页必败；
        // 探不通就地给热点后路指引，不把用户带进注定失败的连接页。
        Thread {
            val reachable = try {
                java.net.Socket().use {
                    it.connect(java.net.InetSocketAddress(info.ip, info.port), BLE_PROBE_TIMEOUT_MS.toInt())
                    true
                }
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (reachable) {
                    ConnectingActivity.start(
                        this,
                        info.ip,
                        info.port,
                        code,
                        intent.getStringExtra(EXTRA_FINGERPRINT).orEmpty(),
                        intent.getStringExtra(EXTRA_NAME).orEmpty(),
                    )
                } else {
                    bleConnected = false // 放开重试闸：连上热点后可直接再输码
                    statusView.text = getString(R.string.pair_input_ble_unreachable)
                }
            }
        }.start()
    }

    private fun connectToPeer(peer: DiscoveryService.DiscoveredPeer, code: String) {
        ConnectingActivity.start(
            this,
            peer.host,
            peer.port,
            code,
            intent.getStringExtra(EXTRA_FINGERPRINT).orEmpty(),
            peer.deviceName,
        )
    }

    companion object {
        private const val PAIR_CODE_LEN = 6
        private const val REQ_BLE_SCAN = 4803
        private const val BLE_TIMEOUT_MS = 10_000L
        private const val BLE_PROBE_TIMEOUT_MS = 2_000L
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_NAME = "name"

        fun startForPeer(context: Context, peer: DiscoveryService.DiscoveredPeer) {
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
