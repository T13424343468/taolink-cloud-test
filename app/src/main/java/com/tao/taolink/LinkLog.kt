package com.tao.tao.link

/**
 * 全系日志口：默认静默，宿主注入实现。
 * 不直接依赖 android.util.Log——纯 JVM 单元测试层也要能装载本类（测试规范·行为探针口径）。
 */
object LinkLog {
    private var sink: (level: String, tag: String, message: String) -> Unit = { _, _, _ -> }

    fun install(impl: (level: String, tag: String, message: String) -> Unit) {
        sink = impl
    }

    fun d(tag: String, message: String) = sink("D", tag, message)

    fun w(tag: String, message: String) = sink("W", tag, message)
}
