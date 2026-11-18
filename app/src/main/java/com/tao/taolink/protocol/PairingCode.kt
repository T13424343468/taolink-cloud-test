package com.tao.tao.link.protocol

import java.security.SecureRandom

/**
 * 配对码生成与校验（协议文档 §2-2 契约）：
 * 被控端生成 6 位码大字显示，主控端输入，被控端全程零输入（需求清单 §1）。
 * 纯逻辑，可测。
 */
object PairingCode {

    private val random = SecureRandom()
    const val LENGTH = 6

    /** 生成 6 位数字码，前导零保留（"012345" 合法）。 */
    fun generate(): String {
        val sb = StringBuilder(LENGTH)
        repeat(LENGTH) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }

    /** 主控端提交的码是否通过本地校验（格式 + 与本端生成码比对）。 */
    fun verify(expected: String, submitted: String): Boolean {
        if (submitted.length != LENGTH || submitted.any { !it.isDigit() }) return false
        return constantTimeEquals(expected, submitted)
    }

    /** 常量时间比较，不给暴力猜码留时序侧信道。 */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
