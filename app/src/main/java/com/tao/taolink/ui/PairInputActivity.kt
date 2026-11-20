package com.tao.tao.link.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tao.tao.link.R
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
        super.onDestroy()
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
            found.isEmpty() -> Toast.makeText(this, R.string.pair_input_need_peer, Toast.LENGTH_LONG).show()
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
