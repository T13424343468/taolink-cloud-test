package com.tao.tao.link

import android.app.Application
import android.util.Log

/**
 * 应用入口：装配全局日志口（LinkLog 默认静默，宿主在此落地 logcat）。
 * 双机实弹 2026-11-19 定因教训：无日志时连接类故障两端全瞎，逐环定因无据可查。
 */
class TaoLinkApp : Application() {

    override fun onCreate() {
        super.onCreate()
        LinkLog.install { level, tag, message ->
            when (level) {
                "W" -> Log.w("TaoLink/$tag", message)
                else -> Log.i("TaoLink/$tag", message)
            }
        }
        installCrashCatcher()
    }

    /** 崩溃自录：真机闪退时把堆栈落盘 filesDir/crash_last.txt，供取证定因（云测模拟器跑不到真机 API 病）。 */
    private fun installCrashCatcher() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val trace = android.util.Log.getStackTraceString(throwable)
                java.io.File(filesDir, "crash_last.txt").writeText(
                    "thread=${thread.name}\n" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date()) + "\n" + trace,
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
