package com.tao.tao.link.video

import android.graphics.Bitmap
import android.media.Image
import java.io.ByteArrayOutputStream

/**
 * 帧编码：画面源统一产 JPEG 帧载荷（协议 §2-6 省流档 JPEG 口径；流畅档硬编推流前同源取帧）。
 * 编码质量档集中一处（编码规范一.5）。
 */
object ImageCodec {

    /** 省流档压缩质量：1 帧/秒带宽预算内的平衡值。 */
    private const val ECO_JPEG_QUALITY = 60

    /** 流畅档压缩质量：投屏帧率下略降质量保帧率。 */
    private const val SMOOTH_JPEG_QUALITY = 80

    fun bitmapToJpeg(bitmap: Bitmap, eco: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, if (eco) ECO_JPEG_QUALITY else SMOOTH_JPEG_QUALITY, out)
        return out.toByteArray()
    }

    /** ImageReader/截屏 Image → JPEG；Image 用完即闭（缓冲归还，不占采集器队列）。 */
    fun imageToJpeg(image: Image, eco: Boolean): ByteArray {
        val buffer = image.planes[0].buffer
        val pixelStride = image.planes[0].pixelStride
        val rowStride = image.planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(buffer)
        val trimmed = if (bitmap.width == image.width) bitmap else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle()
            cropped
        }
        val jpeg = bitmapToJpeg(trimmed, eco)
        image.close()
        return jpeg
    }
}
