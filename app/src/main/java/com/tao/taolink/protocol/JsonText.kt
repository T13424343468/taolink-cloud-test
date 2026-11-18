package com.tao.tao.link.protocol

/**
 * 协议文本消息 JSON 组帧助手：主控端只组不析（解析在对端）。
 * 转义覆盖引号、反斜杠与控制字符，保证载荷合法 JSON。
 */
object JsonText {

    fun escape(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        raw.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    fun field(name: String, value: String): String = "\"$name\":\"${escape(value)}\""

    fun field(name: String, value: Int): String = "\"$name\":$value"

    fun field(name: String, value: Float): String = "\"$name\":$value"

    fun field(name: String, value: Long): String = "\"$name\":$value"

    fun obj(vararg fields: String): String = "{${fields.joinToString(",")}}"
}
