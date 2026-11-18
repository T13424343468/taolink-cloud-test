package com.tao.tao.link.video

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread

/**
 * 流畅档画面源（ScreenSource 契约 SMOOTH）：MediaProjection 投屏采集。
 * 投屏授权每回确认（需求清单 §2 口径）；授权结果（resultCode + data）由界面层取回后传入本类，
 * 本类只管采集与帧回调，不藏授权状态。
 */
class MediaProjectionScreenSource(
    private val context: Context,
    private val resultCode: Int,
    private val data: android.content.Intent,
) : ScreenSource {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var frameCallback: ((ByteArray) -> Unit)? = null
    private var running = false

    override fun start(mode: ScreenSource.Mode): Boolean {
        if (mode != ScreenSource.Mode.SMOOTH) return false
        if (running) return true
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return try {
            val p = manager.getMediaProjection(resultCode, data)
            p?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stop()
                }
            }, null)
            handlerThread = HandlerThread("taolink-smooth-capture").also { it.start() }
            val handler = Handler(handlerThread!!.looper)
            val reader = ImageReader.newInstance(WIDTH_PX, HEIGHT_PX, android.graphics.PixelFormat.RGBA_8888, MAX_IMAGES)
            reader.setOnImageAvailableListener({ readerInstance ->
                val image = try {
                    readerInstance.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }
                if (image != null) {
                    val jpeg = ImageCodec.imageToJpeg(image, eco = false)
                    frameCallback?.invoke(jpeg)
                }
            }, handler)
            val display = p.createVirtualDisplay(
                "taolink-smooth", WIDTH_PX, HEIGHT_PX, DISPLAY_DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
            )
            projection = p
            imageReader = reader
            virtualDisplay = display
            running = true
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }

    override fun stop() {
        running = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        handlerThread?.quitSafely()
        handlerThread = null
    }

    override fun onFrame(callback: (ByteArray) -> Unit) {
        frameCallback = callback
    }

    companion object {
        private const val WIDTH_PX = 720
        private const val HEIGHT_PX = 1280
        private const val DISPLAY_DPI = 160
        private const val MAX_IMAGES = 2
    }
}
