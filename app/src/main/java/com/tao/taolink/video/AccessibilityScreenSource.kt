package com.tao.tao.link.video

import android.os.Handler
import android.os.HandlerThread
import com.tao.tao.link.accessibility.TaoLinkAccessibilityService

/**
 * 省流档画面源（ScreenSource 契约 ECO）：无障碍截屏约 1 帧/秒 JPEG，一次授权长期可用（默认档）。
 * 采集节拍集中一处（编码规范一.5）；服务不可用（无障碍被回收）时 start 返回 false 由上层引导重开。
 */
class AccessibilityScreenSource : ScreenSource {

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var frameCallback: ((ByteArray) -> Unit)? = null
    private var running = false

    override fun start(mode: ScreenSource.Mode): Boolean {
        if (mode != ScreenSource.Mode.ECO) return false
        if (running) return true
        if (!TaoLinkAccessibilityService.isAvailable()) return false
        handlerThread = HandlerThread("taolink-eco-capture").also { it.start() }
        handler = Handler(handlerThread!!.looper)
        running = true
        scheduleCapture()
        return true
    }

    override fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    override fun onFrame(callback: (ByteArray) -> Unit) {
        frameCallback = callback
    }

    private fun scheduleCapture() {
        handler?.postDelayed({
            if (!running) return@postDelayed
            val service = TaoLinkAccessibilityService.current()
            val bitmap = service?.takeScreenshotBitmap()
            if (bitmap != null) {
                val jpeg = ImageCodec.bitmapToJpeg(bitmap, eco = true)
                bitmap.recycle()
                frameCallback?.invoke(jpeg)
            }
            if (running) scheduleCapture()
        }, INTERVAL_MS)
    }

    companion object {
        const val INTERVAL_MS = 1000L
    }
}
