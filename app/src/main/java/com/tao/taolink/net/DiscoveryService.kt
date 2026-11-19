package com.tao.tao.link.net

/** 局域网发现契约（协议文档 §2-1）：NSD 服务名 taolink._tcp，载荷含协议版本、设备名、角色支持位。 */
interface DiscoveryService {
    data class DiscoveredPeer(
        val deviceName: String,
        val host: String,
        val port: Int,
        val protocolVersion: Int,
        val supportsControlled: Boolean,
        val supportsController: Boolean,
    )

    /** onError：发现启动失败回调（R4 失败态触发源），errorCode 为平台原始错误码。 */
    fun startBrowsing(onFound: (DiscoveredPeer) -> Unit, onError: ((Int) -> Unit)? = null)

    fun stopBrowsing()
}
