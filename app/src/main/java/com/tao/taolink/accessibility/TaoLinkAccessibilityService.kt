package com.tao.tao.link.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 被控端无障碍服务（需求清单 §2：无障碍为主授权通道，节点级 + 手势级双通道执行）。
 * 主控指令到达后由 AccessibilityControlExecutor 路由到本服务执行；执行结果回执经控制通道返回。
 */
class TaoLinkAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 被控端只执行指令，不监听系统事件流；事件流零消费，权限最小化
    }

    override fun onInterrupt() {
        // 服务被打断只影响新指令，驻留不变
    }

    /** 节点级：按 viewId 资源名找节点并 ACTION_SET_TEXT（输入类指令）。 */
    fun setNodeTextByViewId(viewId: String, text: String): Boolean {
        val target = findTargetNode(viewId) ?: return false
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 手势级：dispatchGesture 注入（需求清单 §2：点击/长按/滑动）。 */
    fun performGesture(gesture: GestureKind): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val stroke = buildStroke(gesture) ?: return false
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** 键值注入：无障碍通道只放行系统级全局键（返回/主页/最近任务），其余键回执失败不硬造。 */
    fun sendKeyByKeyEvent(keyCode: Int): Boolean = when (keyCode) {
        android.view.KeyEvent.KEYCODE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        android.view.KeyEvent.KEYCODE_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        android.view.KeyEvent.KEYCODE_APP_SWITCH -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }

    /** 无障碍截屏（省流档画面源，API 30+；低版本走投屏档，此路径返回 null）。 */
    fun takeScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var captured: Bitmap? = null
        // 回调走独立执行线程：主线程调用本方法不会因 latch 等待而死锁
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            SCREENSHOT_EXECUTOR,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        captured = screenshot.hardwareBuffer?.let { buffer ->
                            val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            buffer.close()
                            // 硬件位图不落地，转软件位图再压缩，防回收后读失败
                            bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        }
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            },
        )
        latch.await()
        return captured
    }

    private fun buildStroke(gesture: GestureKind): GestureDescription.StrokeDescription? {
        val path = android.graphics.Path()
        return when (gesture) {
            is GestureKind.Tap -> {
                path.moveTo(gesture.x, gesture.y)
                GestureDescription.StrokeDescription(path, 0, TAP_MS)
            }
            is GestureKind.LongPress -> {
                path.moveTo(gesture.x, gesture.y)
                GestureDescription.StrokeDescription(path, 0, LONG_PRESS_MS)
            }
            is GestureKind.Swipe -> {
                path.moveTo(gesture.x, gesture.y)
                path.lineTo(gesture.x2, gesture.y2)
                GestureDescription.StrokeDescription(path, 0, gesture.durationMs.coerceAtLeast(MIN_SWIPE_MS))
            }
        }
    }

    /** 按 viewId 精准定位：先全窗口找精确匹配，找不到取第一个可用节点兜底返回 null。 */
    private fun findTargetNode(viewId: String): AccessibilityNodeInfo? {
        val nodes = rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)
        return nodes?.firstOrNull()
    }

    sealed class GestureKind {
        data class Tap(val x: Float, val y: Float) : GestureKind()
        data class LongPress(val x: Float, val y: Float) : GestureKind()
        data class Swipe(val x: Float, val y: Float, val x2: Float, val y2: Float, val durationMs: Long) : GestureKind()
    }

    companion object {
        private const val TAP_MS = 50L
        private const val LONG_PRESS_MS = 600L
        private const val MIN_SWIPE_MS = 100L

        private var instance: TaoLinkAccessibilityService? = null

        /** 截屏回调执行线程（单线程串行，避免与主线程互等）。 */
        private val SCREENSHOT_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "taolink-screenshot").apply { isDaemon = true }
        }

        /** 服务连接即登记，断开即清；执行层由此判无障碍可用性。 */
        fun current(): TaoLinkAccessibilityService? = instance

        fun isAvailable(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
