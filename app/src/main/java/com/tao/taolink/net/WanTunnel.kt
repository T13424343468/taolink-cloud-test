package com.tao.tao.link.net

/**
 * 外网连接契约（协议文档 §2-7）：P2P 打洞优先，中继只留接口与降级位；
 * 中继服务器涉费方案另行呈报老板拍板，本班不实现（拍板口径）。
 */
interface WanTunnel {
    /** 打洞结果：成功返回直连通道，失败走 [RelayFallback]。 */
    fun punchHole(peerEndpoint: String): Boolean

    /** 中继兜路接口位：仅契约，不实现。 */
    interface RelayFallback {
        fun connect(relayEndpoint: String): Boolean

        fun teardown()
    }
}
