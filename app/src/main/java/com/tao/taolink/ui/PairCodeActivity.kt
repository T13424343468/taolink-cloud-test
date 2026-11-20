package com.tao.tao.link.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tao.tao.link.R
import com.tao.tao.link.net.WifiDirectPairing
import com.tao.tao.link.protocol.DeviceFingerprint
import com.tao.tao.link.protocol.DeviceIdentity
import com.tao.tao.link.protocol.PairingResponder
import com.tao.tao.link.protocol.PairingResponderHolder
import com.tao.tao.link.service.ControlledSessionService

/**
 * 被控端配对码大字显示（需求清单 §1：配对码被控端大字显示、主控端输入，被控端全程零输入）。
 * 老人机场景字号拉满（大道至简：第一眼只见配对码这一件事）。
 * OEM 适配（0.2.9）：进页防御性重起驻留服务 + 回环探活「接收就绪」，
 * 一键申请电池豁免（官方 isIgnoringBatteryOptimizations / ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）。
 *
 * 0.5.0 拍板：发现与组网整层换 Wi-Fi Direct——本页只做两件事：
 * 设 P2P 设备名（TaoLink-指纹前4位，对家名称可辨识）+ 组建成后当客户端时向主控敲门报地址；
 * 蓝牙信标与热点后路整层下线（mDNS/蓝牙/热点拼凑被系统 P2P 直连替代）。
 */
class PairCodeActivity : AppCompatActivity() {

    private lateinit var responder: PairingResponder
    private var p2p: WifiDirectPairing? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair_code)
        responder = PairingResponderHolder.get(
            filesDir,
            DeviceIdentity.ownFingerprint(filesDir, getString(R.string.default_device_name)),
        )
        findViewById<TextView>(R.id.pair_code_text).text = responder.displayCode()
        // 防御性重起：OEM 杀后台后重进本页时，把驻留服务拉回来再听 48190（服务自身幂等）。
        ControlledSessionService.start(this)
        findViewById<Button>(R.id.btn_battery_allow).setOnClickListener { requestBatteryExemption() }
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryBanner()
        probeServeReady()
        startP2pPairing()
    }

    override fun onPause() {
        p2p?.stop() // 离页即停 P2P 会话与敲门，不留常开监听（最小功耗与最小暴露口径一致）
        p2p = null
        super.onPause()
    }

    /**
     * Wi-Fi Direct 配对会话（被控端角色）：本页开着期间监听 P2P 组建——
     * 本机当客户端时向组长（主控）敲门报地址，主控据此回连本机 48190。
     */
    private fun startP2pPairing() {
        if (p2p != null) return
        val controller = WifiDirectPairing(this, WifiDirectPairing.Role.CONTROLLED)
        p2p = controller
        controller.start()
    }

    /** 一键申请电池豁免：先走系统直接授权弹窗，异常时回退到电池优化设置页。 */
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")),
            )
        } catch (_: Exception) {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    /** 未豁免则显示引导横幅和按钮；已豁免全部隐藏（onResume 时回来自动刷新）。 */
    private fun refreshBatteryBanner() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        findViewById<TextView>(R.id.pair_code_battery_banner).visibility =
            if (exempt) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.btn_battery_allow).visibility =
            if (exempt) View.GONE else View.VISIBLE
    }

    /** 回环探活：本机连 48190 端口，通即驻留服务在听；后台线程探，回 UI 线程更字。 */
    private fun probeServeReady() {
        val serveView = findViewById<TextView>(R.id.pair_code_serve)
        Thread {
            val ready = try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", ControlledSessionService.SERVICE_PORT), 500)
                    true
                }
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                serveView.text =
                    if (ready) getString(R.string.pair_code_serve_ready) else getString(R.string.pair_code_serve_down)
            }
        }.start()
    }
}
