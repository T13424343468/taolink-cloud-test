package com.tao.tao.link.protocol

/**
 * 连接会话状态机（协议文档 §1，唯一状态源）。
 * 状态迁移只经 transition 改变；迁移不合法时返回 false，调用方按原态继续。
 */
enum class SessionState {
    IDLE,
    DISCOVERED,
    PAIRING,
    CONNECTED,
    CONTROLLING,
    DISCONNECTED,
    SELF_HEALING,
    /** 外网打洞中（DISCOVERED 的外网子态，不新增顶层态）。 */
    WAN_DIRECT,
    /** 中继兜路中（接口位，中继实现另行呈报）。 */
    WAN_RELAY,
    ;

    fun canTransitionTo(next: SessionState): Boolean = allowedFrom.getValue(this).contains(next)

    companion object {
        // 迁移表放 companion：枚举常量初始化完成后才可能被访问，规避「Enum entry is uninitialized」。
        private val allowedFrom: Map<SessionState, Set<SessionState>> = mapOf(
            IDLE to setOf(DISCOVERED, WAN_DIRECT, WAN_RELAY),
            DISCOVERED to setOf(PAIRING, DISCONNECTED),
            WAN_DIRECT to setOf(PAIRING, DISCONNECTED),
            WAN_RELAY to setOf(PAIRING, DISCONNECTED),
            PAIRING to setOf(CONNECTED, DISCONNECTED),
            CONNECTED to setOf(CONTROLLING, DISCONNECTED, SELF_HEALING),
            CONTROLLING to setOf(CONNECTED, DISCONNECTED, SELF_HEALING),
            DISCONNECTED to setOf(SELF_HEALING, IDLE),
            SELF_HEALING to setOf(CONNECTED, DISCONNECTED),
        )
    }
}
