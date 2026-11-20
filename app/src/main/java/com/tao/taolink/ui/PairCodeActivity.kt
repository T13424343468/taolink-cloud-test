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
import com.tao.tao.link.protocol.DeviceFingerprint
import com.tao.tao.link.protocol.DeviceIdentity
import com.tao.tao.link.protocol.PairingResponder
import com.tao.tao.link.protocol.PairingResponderHolder
import com.tao.tao.link.service.ControlledSessionService

/**
 * 被控端配对码大字显示（需求清单 §1：配对码被控端大字显示、主控端输入，被控端全程零输入）。
 * 老人机场景字号拉满（大道至简：第一眼只见配对码这一件事）。
 * 直连后路（0.2.6 真机复验发现被 OEM/路由器拦时）：屏幕同时显示本机地址，
 * 对方在输码页填地址即直达，不依赖发现链路（§6 换，不换目标）。
 * OEM 适配（0.2.9）：进页防御性重起驻留服务 + 回环探活「接收就绪」，
 * 一键申请电池豁免（官方 isIgnoringBatteryOptimizations / ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）。
 */
class PairCodeActivity : AppCompatActivity() {

    private lateinit var responder: PairingResponder

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
        // 热点后路：路由器拦设备互访时，被控机自开热点当网，主控连热点即直达（不碰路由器）。
        findViewById<Button>(R.id.btn_hotspot_open).setOnClickListener { openHotspotSettings() }
    }

    override fun onResume() {
        super.onResume()
        refreshIpLine()
        refreshBatteryBanner()
        probeServeReady()
    }

    /** 开热点设置页：先试热点专属面板，厂商不带时回退无线设置总页。 */
    private fun openHotspotSettings() {
        try {
            startActivity(Intent("android.settings.TETHER_SETTINGS"))
        } catch (_: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }
    }

    /** 地址行随 onResume 刷新：热点开关后本机地址会变，回到本页立即重取。 */
    private fun refreshIpLine() {
        findViewById<TextView>(R.id.pair_code_ip).text =
            getString(R.string.pair_code_ip_label, localIpv4() ?: getString(R.string.pair_code_ip_unset))
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

    /** 取本机 IPv4：优先 WiFi，多网卡时取第一个站点内地址；拿不到显示未取到，不空指。 */
    private fun localIpv4(): String? =
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
}
