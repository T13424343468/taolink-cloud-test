package com.tao.tao.link.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import androidx.core.content.ContextCompat
import com.tao.tao.link.LinkLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Wi-Fi Direct 配对链（0.5.0 拍板：发现与组网整层换系统 P2P 直连，
 * 替代 mDNS+蓝牙信令+热点拼凑；配对码、指纹校验、传输层不动）。
 *
 * 系统包了原来信令要干的活：发现（discoverPeers）与组网（协商组长、分 IP），
 * 我们只剩薄薄一层握手：
 * - 主控端尽量当客户端（groupOwnerIntent=0），组建成后直连组长地址（即被控机 48190）；
 * - 万一主控被推成组长，被控机（客户端）向主控敲门（KNOCK_PORT）报出自己，
 *   主控从敲门连接取对方组内地址，再回连被控机的 48190——两条路都通，不赌协商结果。
 *
 * 时序坑（真机口径）：双方同时 discoverPeers 可能互相看不见，发现页/输码页用
 * [rediscover] 周期重扫兜底；这是调系统 API 的时序活，不是自建信令。
 */
class WifiDirectPairing(
    private val context: Context,
    private val role: Role,
    private val onPeersChanged: (List<WifiP2pDevice>) -> Unit = {},
    private val onLinked: (host: String) -> Unit = {},
    private val onFailed: (reason: String) -> Unit = {},
) {
    enum class Role { MASTER, CONTROLLED }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false
    private var knockServer: ServerSocket? = null

    @Volatile
    private var linked = false

    private val peerLock = Any()
    private var latestPeers: List<WifiP2pDevice> = emptyList()

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) onFailed("p2p_disabled")
                }
            }
        }
    }

    fun start() {
        val mgr = manager ?: run { onFailed("p2p_unavailable"); return }
        channel = mgr.initialize(context, context.mainLooper, null)
        ContextCompat.registerReceiver(
            context,
            eventReceiver,
            IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
        // 两端都进发现态：被控端也要 discoverPeers 才会对外可见（P2P 设备只在
        // Wi-Fi Direct 激活时可被发现），主控端扫、被控端可被发现，错开同时扫的时序坑。
        discover()
    }

    private fun discover() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                LinkLog.w(TAG, "discoverPeers fail reason=$reason")
                onFailed("discover_$reason")
            }
        })
    }

    /** 周期重扫入口：先停再起，救「双方同时扫互相看不见」的时序坑。 */
    fun rediscover() {
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching { mgr.stopPeerDiscovery(ch, null) }
        discover()
    }

    fun peersSnapshot(): List<WifiP2pDevice> = synchronized(peerLock) { latestPeers }

    private fun requestPeers() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.requestPeers(ch) { peers ->
            synchronized(peerLock) { latestPeers = peers.deviceList.toList() }
            onPeersChanged(latestPeers)
        }
    }

    /** 主控端发起直连：groupOwnerIntent=0 尽量当客户端，直连组长（被控机）地址。 */
    fun connect(device: WifiP2pDevice) {
        val mgr = manager ?: return
        val ch = channel ?: return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0
        }
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                LinkLog.w(TAG, "connect fail reason=$reason")
                onFailed("connect_$reason")
            }
        })
    }

    private fun requestConnectionInfo() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.requestConnectionInfo(ch) { info ->
            if (!info.groupFormed || linked) return@requestConnectionInfo
            if (info.isGroupOwner) {
                // 本机是组长：主控侧开敲门监听等被控机报地址；被控侧当组长则主控会直连本机，无需动作
                if (role == Role.MASTER) startKnockServer()
            } else {
                val go = info.groupOwnerAddress ?: return@requestConnectionInfo
                val host = go.hostAddress ?: return@requestConnectionInfo
                if (role == Role.MASTER) {
                    linked = true
                    onLinked(host) // 主控当客户端：组长就是被控机
                } else {
                    knockMaster(host) // 被控当客户端：向组长（主控）敲门报地址
                }
            }
        }
    }

    /** 主控意外当组长：开敲门监听，等被控机报地址。 */
    private fun startKnockServer() {
        if (knockServer != null) return
        Thread {
            try {
                val server = ServerSocket()
                server.bind(InetSocketAddress(KNOCK_PORT))
                knockServer = server
                while (!server.isClosed) {
                    val socket = server.accept()
                    try {
                        socket.soTimeout = KNOCK_READ_TIMEOUT_MS
                        val line = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                        val peerIp = socket.inetAddress?.hostAddress
                        if (line?.startsWith(KNOCK_MAGIC) == true && peerIp != null && !linked) {
                            linked = true
                            onLinked(peerIp)
                        }
                    } catch (_: Exception) {
                        // 单个敲门连接坏了不致命，继续等下一个
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            } catch (e: Exception) {
                LinkLog.w(TAG, "knock server: ${e.message}")
            }
        }.apply { isDaemon = true; name = "taolink-knock" }.start()
    }

    /** 被控当客户端：向组长（主控）敲门，把主控引回本机 48190。 */
    private fun knockMaster(goHost: String) {
        Thread {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(goHost, KNOCK_PORT), KNOCK_CONNECT_TIMEOUT_MS)
                    socket.getOutputStream().write("$KNOCK_MAGIC\n".toByteArray())
                    socket.getOutputStream().flush()
                }
            } catch (e: Exception) {
                // 敲门失败不致命：主控若当客户端会直连；组长场景下用户重试即可
                LinkLog.w(TAG, "knock fail: ${e.message}")
            }
        }.apply { isDaemon = true; name = "taolink-knock" }.start()
    }

    fun stop() {
        runCatching { knockServer?.close() }
        knockServer = null
        if (registered) {
            runCatching { context.unregisterReceiver(eventReceiver) }
            registered = false
        }
        runCatching {
            val mgr = manager ?: return@runCatching
            val ch = channel ?: return@runCatching
            mgr.stopPeerDiscovery(ch, null)
        }
        channel = null
    }

    companion object {
        private const val TAG = "WifiDirectPairing"
        private const val KNOCK_PORT = 48191
        private const val KNOCK_MAGIC = "TAOLINK_KNOCK"
        private const val KNOCK_CONNECT_TIMEOUT_MS = 2_000
        private const val KNOCK_READ_TIMEOUT_MS = 2_000
    }
}
