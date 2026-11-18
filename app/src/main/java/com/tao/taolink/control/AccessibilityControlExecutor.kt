package com.tao.tao.link.control

import com.tao.tao.link.accessibility.TaoLinkAccessibilityService

/**
 * 操控指令执行（ControlChannel 契约的被控端实现）：
 * 节点级（ACTION_SET_TEXT 系）+ 手势级（dispatchGesture）双通道，指令执行结果即回执（协议 §2-5）。
 */
class AccessibilityControlExecutor : ControlChannel {

    override fun setNodeText(nodeId: String, text: String): Boolean {
        val service = TaoLinkAccessibilityService.current() ?: return false
        return service.setNodeTextByViewId(nodeId, text)
    }

    override fun dispatchGesture(gesture: ControlChannel.Gesture): Boolean {
        val service = TaoLinkAccessibilityService.current() ?: return false
        val kind = when (gesture.kind) {
            ControlChannel.Gesture.Kind.TAP ->
                TaoLinkAccessibilityService.GestureKind.Tap(gesture.x, gesture.y)
            ControlChannel.Gesture.Kind.LONG_PRESS ->
                TaoLinkAccessibilityService.GestureKind.LongPress(gesture.x, gesture.y)
            ControlChannel.Gesture.Kind.SWIPE ->
                TaoLinkAccessibilityService.GestureKind.Swipe(gesture.x, gesture.y, gesture.x2, gesture.y2, gesture.durationMs)
        }
        return service.performGesture(kind)
    }

    override fun sendKey(keyCode: Int): Boolean {
        // 键值注入经无障碍 KEYCODE 通道（dispatchGesture 覆盖不到的输入类指令）；无服务在位即回执失败
        val service = TaoLinkAccessibilityService.current() ?: return false
        return service.sendKeyByKeyEvent(keyCode)
    }
}
