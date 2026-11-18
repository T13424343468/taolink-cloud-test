package com.tao.tao.link.control

import kotlin.math.hypot

/**
 * 主控端触屏事件 → 操控手势映射（需求清单 §3：点击/滑动/输入）。
 * 纯逻辑可测；坐标换算按画面 fitCenter 黑边模型（黑边区域返回 null，不越界注入）。
 */
object GestureMapper {

    /** 位移小于该值视为原地触摸（点击/长按）。 */
    const val SLOP_PX = 8f

    /** 按压超过该时长视为长按。 */
    const val LONG_PRESS_MS = 400L

    data class TouchPoint(val x: Float, val y: Float, val tMs: Long)

    fun classify(down: TouchPoint, up: TouchPoint): ControlChannel.Gesture.Kind {
        val distance = hypot((up.x - down.x).toDouble(), (up.y - down.y).toDouble()).toFloat()
        val duration = (up.tMs - down.tMs).coerceAtLeast(0)
        return when {
            distance > SLOP_PX -> ControlChannel.Gesture.Kind.SWIPE
            duration >= LONG_PRESS_MS -> ControlChannel.Gesture.Kind.LONG_PRESS
            else -> ControlChannel.Gesture.Kind.TAP
        }
    }

    /** 视图坐标 → 对端屏幕坐标；返回 null 表示落在黑边（fitCenter 留白），不产生操控。 */
    fun toRemoteCoords(x: Float, y: Float, viewW: Int, viewH: Int, frameW: Int, frameH: Int): Pair<Float, Float>? {
        if (viewW <= 0 || viewH <= 0 || frameW <= 0 || frameH <= 0) return null
        val scale = minOf(viewW.toFloat() / frameW, viewH.toFloat() / frameH)
        if (scale <= 0f) return null
        val drawW = frameW * scale
        val drawH = frameH * scale
        val offsetX = (viewW - drawW) / 2f
        val offsetY = (viewH - drawH) / 2f
        val rx = (x - offsetX) / scale
        val ry = (y - offsetY) / scale
        if (rx < 0f || ry < 0f || rx >= frameW || ry >= frameH) return null
        return rx to ry
    }

    /** 触摸轨迹 → 手势；起止点任一越界返回 null。 */
    fun toGesture(
        down: TouchPoint,
        up: TouchPoint,
        viewW: Int,
        viewH: Int,
        frameW: Int,
        frameH: Int,
    ): ControlChannel.Gesture? {
        val start = toRemoteCoords(down.x, down.y, viewW, viewH, frameW, frameH) ?: return null
        val end = toRemoteCoords(up.x, up.y, viewW, viewH, frameW, frameH) ?: return null
        val kind = classify(down, up)
        return ControlChannel.Gesture(
            kind = kind,
            x = start.first,
            y = start.second,
            x2 = end.first,
            y2 = end.second,
            durationMs = (up.tMs - down.tMs).coerceAtLeast(0),
        )
    }
}
