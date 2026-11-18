package com.tao.tao.link.control

/**
 * 操控指令契约（协议文档 §2-5）：双通道——节点级精准输入 + 手势级注入；
 * 指令带 seq，对端回执 ACK，丢失重发。
 */
interface ControlChannel {
    /** 手势指令：点击 / 长按 / 滑动。 */
    data class Gesture(
        val kind: Kind,
        val x: Float,
        val y: Float,
        val x2: Float = 0f,
        val y2: Float = 0f,
        val durationMs: Long = 0,
    ) {
        enum class Kind { TAP, LONG_PRESS, SWIPE }
    }

    /** 节点级：对无障碍节点树精准设值（ACTION_SET_TEXT 系）。 */
    fun setNodeText(nodeId: String, text: String): Boolean

    /** 手势级：dispatchGesture 注入（覆盖无障碍点不动的系统深处页面走调试通道补位）。 */
    fun dispatchGesture(gesture: Gesture): Boolean

    fun sendKey(keyCode: Int): Boolean
}
