package com.tao.tao.link.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.tao.tao.link.control.GestureMapper
import kotlin.math.roundToInt

/**
 * 对端画面渲染（省流档）：View 内绘制当前帧 Bitmap，fitCenter 黑边模型与 GestureMapper 同口径。
 * 触控事件回调给宿主（宿主经 GestureMapper 换算后下发操控帧）；黑边触摸不上报。
 */
class RemoteScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface TouchSink {
        /** 宿主收到归一后的对端坐标；黑边触摸不回调。 */
        fun onTouch(down: GestureMapper.TouchPoint, up: GestureMapper.TouchPoint)
    }

    var touchSink: TouchSink? = null
    var frameSize: Pair<Int, Int> = 0 to 0

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val blackFill = Paint().apply { color = Color.BLACK }
    @Volatile private var frameBitmap: android.graphics.Bitmap? = null
    private var downPoint: GestureMapper.TouchPoint? = null

    fun showFrame(bitmap: android.graphics.Bitmap) {
        frameBitmap = bitmap
        frameSize = bitmap.width to bitmap.height
        postInvalidate()
    }

    fun clearFrame() {
        frameBitmap = null
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = frameBitmap ?: return
        canvas.drawRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), blackFill)
        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        val left = ((width - drawW) / 2f).roundToInt()
        val top = ((height - drawH) / 2f).roundToInt()
        canvas.drawBitmap(bmp, null, RectF(left.toFloat(), top.toFloat(), left + drawW, top + drawH), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downPoint = GestureMapper.TouchPoint(event.x, event.y, System.currentTimeMillis())
                return true
            }
            MotionEvent.ACTION_UP -> {
                val down = downPoint ?: return false
                downPoint = null
                val up = GestureMapper.TouchPoint(event.x, event.y, System.currentTimeMillis())
                touchSink?.onTouch(down, up)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
