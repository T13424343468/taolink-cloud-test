package com.tao.tao.link.protocol

/**
 * 配对契约（协议文档 §2-2）：
 * 被控端大字显示 6 位码、主控端输入、被控端全程零输入（需求清单 §1）。
 */
interface PairingChannel {
    /** 被控端：生成并展示 6 位配对码（大字显示）。 */
    fun displayCode(): String

    /** 主控端：输入对端配对码，双方各自推导会话密钥。 */
    fun submitCode(code: String)

    /** 配对结果回执。 */
    fun onPairResult(connected: Boolean)
}
