package com.tao.tao.link.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.tao.tao.link.LinkLog
import com.tao.tao.link.protocol.PROTOCOL_VERSION

/**
 * 局域网发现（契约 #1 DiscoveryService 实现）：NSD/mDNS，服务类型 `_taolink._tcp`。
 * TXT 载荷：ver=协议版本、roles=c/d（c=可主控，d=可被控）。
 * 源端口播声明本端角色支持位；浏览解析对端口播，双端共用一件不另造（归一口径）。
 */
class NsdDiscoveryController(context: Context) : DiscoveryService {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val resolving = HashSet<String>()

    override fun startBrowsing(onFound: (DiscoveryService.DiscoveredPeer) -> Unit, onError: ((Int) -> Unit)?) {
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                LinkLog.d(TAG, "发现已启动：$serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                LinkLog.w(TAG, "发现启动失败：$serviceType err=$errorCode")
                discoveryListener = null
                onError?.invoke(errorCode)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                LinkLog.d(TAG, "服务离线：${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                discoveryListener = null
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!resolving.add(serviceInfo.serviceName)) return
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        resolving.remove(info.serviceName)
                        LinkLog.w(TAG, "解析失败：${info.serviceName} err=$errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        resolving.remove(info.serviceName)
                        val peer = toPeer(info) ?: return
                        onFound(peer)
                    }
                }
                // 平台对 resolveService 新旧两个重载同时标废弃（推荐并入发现流程，本期不改发现架构）；
                // 分支内隔离压点，迁移注记：升架构时随发现重设计一起动（同 resolveHost 口径）。
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    nsdManager.resolveService(serviceInfo, DIRECT_EXECUTOR, listener)
                } else {
                    nsdManager.resolveService(serviceInfo, listener)
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override fun stopBrowsing() {
        val listener = discoveryListener ?: return
        nsdManager.stopServiceDiscovery(listener)
        discoveryListener = null
    }

    /** 本端作为对端口播：声明设备名、角色支持位与监听端口。服务停必随 unregister() 撤播（R1 验收：对端列表 30 秒内消失）。 */
    fun register(deviceName: String, supportsController: Boolean, supportsControlled: Boolean, port: Int) {
        if (registrationListener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(TXT_VER, PROTOCOL_VERSION.toString())
            setAttribute(TXT_ROLES, rolesValue(supportsController, supportsControlled))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                LinkLog.d(TAG, "口播已注册：${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registrationListener = null
                LinkLog.w(TAG, "口播注册失败：${serviceInfo.serviceName} err=$errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                registrationListener = null
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                LinkLog.w(TAG, "口播注销失败：${serviceInfo.serviceName} err=$errorCode")
            }
        }
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /** 服务停或退出「让人帮」时撤播：对端发现列表随之消失（NSD service-lost，不超 30 秒）。 */
    fun unregister() {
        val listener = registrationListener ?: return
        registrationListener = null
        nsdManager.unregisterService(listener)
    }

    private fun toPeer(info: NsdServiceInfo): DiscoveryService.DiscoveredPeer? {
        val host = resolveHost(info) ?: return null
        val attrs = info.attributes
        val ver = attrs[TXT_VER]?.decodeToString()?.toIntOrNull() ?: return null
        if (ver != PROTOCOL_VERSION) {
            LinkLog.w(TAG, "协议版本不符，跳过：${info.serviceName} ver=$ver")
            return null
        }
        val roles = attrs[TXT_ROLES]?.decodeToString().orEmpty()
        return DiscoveryService.DiscoveredPeer(
            deviceName = info.serviceName,
            host = host,
            port = info.port,
            protocolVersion = ver,
            supportsControlled = roles.contains(ROLE_CONTROLLED),
            supportsController = roles.contains(ROLE_CONTROLLER),
        )
    }

    private fun resolveHost(info: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.firstOrNull()?.hostAddress
        } else {
            // API 26-33 无 hostAddresses 可用；分支隔离遗留调用并留痕（构建坑册 #45 可空口径同源）。
            @Suppress("DEPRECATION")
            info.host?.hostAddress
        }

    private fun rolesValue(controller: Boolean, controlled: Boolean): String =
        buildString {
            if (controller) append(ROLE_CONTROLLER)
            if (controlled) append(ROLE_CONTROLLED)
        }

    companion object {
        private const val TAG = "NsdDiscovery"
        const val SERVICE_TYPE = "_taolink._tcp."
        const val TXT_VER = "ver"
        const val TXT_ROLES = "roles"
        const val ROLE_CONTROLLER = "c"
        const val ROLE_CONTROLLED = "d"

        /** 解析回调直跑执行器：NSD 回调本就在系统线程，无需另起线程。 */
        private val DIRECT_EXECUTOR = java.util.concurrent.Executor { it.run() }
    }
}
