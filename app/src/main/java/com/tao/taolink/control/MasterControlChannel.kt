package com.tao.tao.link.control

import com.tao.tao.link.protocol.FrameType
import com.tao.tao.link.protocol.JsonText
import com.tao.tao.link.protocol.Route

/**
 * 主控端操控指令下发（契约 #5 ControlChannel 主控侧实现）。
 * 返回 true = 指令已入发送队列并进入回执追踪；执行结果以 ACK 为准，超次放弃经会话层上报 UI。
 * 文本载荷为 JSON（协议文档 §0 文本消息口径），本端只组不析。
 */
class MasterControlChannel(
    private val sendTracked: (payload: ByteArray) -> Long,
) : ControlChannel {

    override fun setNodeText(nodeId: String, text: String): Boolean {
        if (nodeId.isBlank() && text.isEmpty()) return false
        val payload = JsonText.obj(
            JsonText.field("action", ACTION_SET_TEXT),
            JsonText.field("node", nodeId),
            JsonText.field("text", text),
        ).toByteArray(Charsets.UTF_8)
        return trackOrFalse(payload)
    }

    override fun dispatchGesture(gesture: ControlChannel.Gesture): Boolean {
        val payload = JsonText.obj(
            JsonText.field("action", ACTION_GESTURE),
            JsonText.field("kind", gesture.kind.name),
            JsonText.field("x", gesture.x),
            JsonText.field("y", gesture.y),
            JsonText.field("x2", gesture.x2),
            JsonText.field("y2", gesture.y2),
            JsonText.field("durationMs", gesture.durationMs),
        ).toByteArray(Charsets.UTF_8)
        return trackOrFalse(payload)
    }

    override fun sendKey(keyCode: Int): Boolean {
        if (keyCode < 0) return false
        val payload = JsonText.obj(
            JsonText.field("action", ACTION_KEY),
            JsonText.field("code", keyCode),
        ).toByteArray(Charsets.UTF_8)
        return trackOrFalse(payload)
    }

    private fun trackOrFalse(payload: ByteArray): Boolean {
        val seq = sendTracked(payload)
        return seq > 0
    }

    companion object {
        // 指令动作名（协议文档 §2-5 双通道），与被控端执行侧字符串分支同源维护。
        const val ACTION_SET_TEXT = "set_text"
        const val ACTION_GESTURE = "gesture"
        const val ACTION_KEY = "key"
        val COMMAND_FRAME_TYPE: FrameType = FrameType.COMMAND
        val CONTROL_ROUTE: Route = Route.CONTROL
    }
}
