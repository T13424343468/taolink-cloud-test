package com.tao.tao.link.control

/**
 * 指令帧载荷解析与执行（协议 §2-5：指令带 seq，执行结果即回执）。
 * 载荷布局：1B 指令类型 + 载荷。
 * - 0 节点级：UTF-8 `viewId<TAB>text`
 * - 1 手势级：UTF-8 `kind x y x2 y2 durationMs`（空格分六段，kind 取 0/1/2）
 * - 2 键值：4B 大端 keyCode
 * 解析纯逻辑可测；执行落到 AccessibilityControlExecutor。
 */
object ControlDispatcher {

    private const val CMD_SET_TEXT: Byte = 0
    private const val CMD_GESTURE: Byte = 1
    private const val CMD_SEND_KEY: Byte = 2

    private const val GESTURE_TAP: Byte = 0
    private const val GESTURE_LONG_PRESS: Byte = 1
    private const val GESTURE_SWIPE: Byte = 2

    fun dispatch(payload: ByteArray, executor: ControlChannel): Boolean {
        if (payload.isEmpty()) return false
        return when (payload[0]) {
            CMD_SET_TEXT -> dispatchSetText(payload, executor)
            CMD_GESTURE -> dispatchGesture(payload, executor)
            CMD_SEND_KEY -> dispatchSendKey(payload, executor)
            else -> false
        }
    }

    private fun dispatchSetText(payload: ByteArray, executor: ControlChannel): Boolean {
        val text = String(payload, 1, payload.size - 1, Charsets.UTF_8)
        val sep = text.indexOf('\t')
        if (sep <= 0) return false
        val viewId = text.substring(0, sep)
        val value = text.substring(sep + 1)
        return executor.setNodeText(viewId, value)
    }

    private fun dispatchGesture(payload: ByteArray, executor: ControlChannel): Boolean {
        val text = String(payload, 1, payload.size - 1, Charsets.UTF_8)
        // 线格式：kind x y x2 y2 duration，空格分六段
        val parts = text.split(' ')
        if (parts.size != 6) return false
        val kind = when (parts[0]) {
            "0" -> ControlChannel.Gesture.Kind.TAP
            "1" -> ControlChannel.Gesture.Kind.LONG_PRESS
            "2" -> ControlChannel.Gesture.Kind.SWIPE
            else -> return false
        }
        val values = parts.drop(1).mapNotNull { it.toFloatOrNull() }
        if (values.size != 5) return false
        val gesture = ControlChannel.Gesture(kind, values[0], values[1], values[2], values[3], values[4].toLong())
        return executor.dispatchGesture(gesture)
    }

    private fun dispatchSendKey(payload: ByteArray, executor: ControlChannel): Boolean {
        if (payload.size != 5) return false
        var keyCode = 0
        for (i in 1..4) {
            keyCode = (keyCode shl 8) or (payload[i].toInt() and 0xFF)
        }
        return executor.sendKey(keyCode)
    }
}
