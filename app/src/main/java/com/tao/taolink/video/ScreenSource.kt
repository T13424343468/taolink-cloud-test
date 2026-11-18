package com.tao.tao.link.video

/**
 * 画面双档契约（协议文档 §2-6）：
 * 省流档=无障碍截屏约 1 帧/秒（一次授权长期可用，默认档）；
 * 流畅档=MediaProjection（每回确认）；调试通道路线留接口位（免确认）。
 */
interface ScreenSource {
    enum class Mode {
        /** 省流档：无障碍截屏，一次授权长期可用。 */
        ECO,

        /** 流畅档：投屏通道，每回点「开始」。 */
        SMOOTH,

        /** 调试通道路线：免每回确认，接口位。 */
        ADB,
    }

    fun start(mode: Mode): Boolean

    fun stop()

    /** 帧回调：帧格式由实现声明，二进制透传。 */
    fun onFrame(callback: (ByteArray) -> Unit)
}
