package com.example.roundkb

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志收集：
 *  - Java 层未捕获异常：写 filesDir/crash_log.txt（保留最近若干条）+ 崩溃计数；
 *  - native 崩溃（SIGSEGV 等直接杀进程，Java handler 抓不到）：由 RimeEngine 的
 *    「native 调用标记文件」兜底——进入 native 前落标记、离开时删除，下次启动
 *    发现残留即判定上次死于 native，自动禁用 Rime。
 *  - 崩溃计数 ≥ [CRASH_LIMIT] 后自动进入安全模式（跳过全部 native 初始化），
 *    保证键盘永远可用；正常存活 30 秒自动清零计数。
 */
object CrashLogger {

    private const val CRASH_LIMIT = 2        // 连续崩溃 N 次进入安全模式
    private const val MAX_LOG_BYTES = 64 * 1024
    private const val HEALTH_DELAY_MS = 30_000L

    private var sp: SharedPreferences? = null
    private var logFile: File? = null
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            val app = context.applicationContext
            sp = app.getSharedPreferences("crash_meta", 0)
            logFile = File(app.filesDir, "crash_log.txt")
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try { record(t, e) } catch (_: Throwable) {}
                prev?.uncaughtException(t, e) ?: kotlin.system.exitProcess(2)
            }
        }
    }

    /** 进程健康存活标记：主线程延迟 30s 清零崩溃计数 */
    fun markHealthy() {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        h.postDelayed({
            try { sp()?.edit()?.putInt("count", 0)?.apply() } catch (_: Throwable) {}
        }, HEALTH_DELAY_MS)
    }

    /** 是否应进入安全模式（禁用 native 引擎） */
    fun safeMode(): Boolean = (sp()?.getInt("count", 0) ?: 0) >= CRASH_LIMIT

    /** 最近一次崩溃的摘要（一行），无则 null */
    fun lastSummary(): String? {
        if ((sp()?.getInt("count", 0) ?: 0) <= 0) return null
        val txt = logFile?.takeIf { it.exists() }?.readText() ?: return "检测到异常退出"
        // 取第一条（最新在前）的头两行
        val block = txt.substringBefore("\n\n").lines()
        return block.take(2).joinToString(" ").take(120)
    }

    fun nativeCrashFlagFile(context: Context): File =
        File(context.applicationContext.filesDir, ".native_call")

    fun logText(): String {
        return logFile?.takeIf { it.exists() }?.readText()
            ?: "暂无崩溃记录"
    }

    fun clear() {
        try {
            logFile?.delete()
            sp()?.edit()?.clear()?.apply()
        } catch (_: Throwable) {}
    }

    private fun sp(): SharedPreferences? = sp

    private fun record(t: Thread, e: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val ver = sp?.let { it.getString("ver", "") } ?: ""
        val sb = StringBuilder()
        sb.append("---- 崩溃 $time ----\n")
        sb.append("线程: ${t.name}\n")
        sb.append("${e.javaClass.name}: ${e.message ?: ""}\n")
        val st = e.stackTrace
        for (i in 0 until minOf(st.size, 24)) sb.append("  at ${st[i]}\n")
        var cause = e.cause
        var depth = 0
        while (cause != null && depth++ < 3) {
            sb.append("Caused by: ${cause.javaClass.name}: ${cause.message ?: ""}\n")
            val cs = cause.stackTrace
            for (i in 0 until minOf(cs.size, 8)) sb.append("  at ${cs[i]}\n")
            cause = cause.cause
        }
        sb.append("\n")
        val f = logFile ?: return
        try {
            val old = if (f.exists() && f.length() > MAX_LOG_BYTES)
                f.readText().substringBefore("---- 崩溃") else
                (if (f.exists()) f.readText() else "")
            f.writeText(sb.toString() + old)
        } catch (_: Throwable) {}
        try {
            sp?.edit()?.putInt("count", (sp?.getInt("count", 0) ?: 0) + 1)?.apply()
        } catch (_: Throwable) {}
    }

    /** 记录版本号（install 时由调用方提供，崩溃头里显示） */
    fun setVersion(v: String) {
        try { sp()?.edit()?.putString("ver", v)?.apply() } catch (_: Throwable) {}
    }
}
