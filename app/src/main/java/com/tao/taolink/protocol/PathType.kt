package com.tao.tao.link.protocol

/**
 * 连接路径类型（拍板 2026-11-18 12:27 Y4：配对记录增「最后连接时间+路径类型」，
 * 主页「最近连过」展示依据）。tag 用于账目落盘，稳定不改名。
 */
enum class PathType(val tag: String) {
    /** 局域网直连。 */
    LAN("lan"),

    /** 外网直连（IPv6 直连/打洞成功）。 */
    WAN_DIRECT("wan_direct"),

    /** 中继兜底（免费公共服务转发，兜底路径不破密）。 */
    WAN_RELAY("wan_relay");

    companion object {
        /** 账目反解：未知 tag 返回 null（旧账无路径列，按未记录处理）。 */
        fun fromTag(tag: String): PathType? = values().firstOrNull { it.tag == tag }
    }
}
