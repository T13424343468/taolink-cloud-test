package com.tao.tao.link.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 省流档逐帧解码（契约 #6 省流档主控侧）：JPEG 载荷 → Bitmap，直接可渲染。
 * 帧识别口径：载荷以 JPEG SOI（0xFFD8）开头走本解码；其余按流畅档 H.264 处理（见 SmoothScreenDecoder）。
 */
object EcoFrameDecoder {

    fun isJpeg(payload: ByteArray): Boolean =
        payload.size >= 2 && payload[0] == 0xFF.toByte() && payload[1] == 0xD8.toByte()

    fun decode(payload: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(payload, 0, payload.size)
}
