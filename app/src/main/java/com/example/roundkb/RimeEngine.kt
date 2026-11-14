package com.example.roundkb

import android.content.Context
import android.os.Build
import com.osfans.trime.core.Rime
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * librime 引擎管理（native 库来自同文输入法 trime v3.3.13）。
 *
 * 生命周期：[ensureStarted] 在后台线程复制 assets → 启动 Rime → 置 ready。
 * 词库已在构建期预编译（assets/rime/build 下的 .bin 产物），设备端只做“加载”不再“现场编译”，
 * 因此每次冷启动都是秒级（手表上现场编译 55 万词要分钟级，是“打开卡很久”的根因）。
 * 主线程所有 API 调用经 [lock] 串行化，未 ready 时返回 null/空，由调用方回退内置引擎。
 */
object RimeEngine {

    /** assets/rime 数据版本：改了 schema 或词库后 +1，触发重新复制。
     *  v3 布局：只带预编译产物（table/reverse，格式与 librime 1.17 兼容），
     *  不带词库源文件——源文件存在会让设备端按当前内容重算 checksum，
     *  与预编译产物里记录的不一致 → 触发 55 万词全量重编译（OOM 闪退的根因）；
     *  源文件不存在时 librime 直接复用产物内记录的 checksum（官方预编译分发模式）。 */
    private const val DATA_VER = "3"

    /** X11 keycode：BackSpace / Return / Space */
    const val KC_BACKSPACE = 0xFF08
    const val KC_RETURN = 0xFF0D
    const val KC_SPACE = 0x20

    /** 一次按键后的引擎快照 */
    class Snapshot(
        /** rime 要求上屏的文本（无则 null） */
        val commit: String?,
        /** 原始输入串（空 = 无组合） */
        val rawInput: String,
        /** 候选词（全局序，下标即 selectCandidate 的参数） */
        val candidates: List<String>,
        /** 预览串（commit_text_preview，无则 null） */
        val preview: String?,
    )

    @Volatile var ready = false
        private set

    /**
     * 引擎自检通过（真实按键 → 有组合串有候选）。只有 verified 为 true 键盘才走 rime，
     * 否则一律用内置引擎——保证任何情况下中文输入都可用，不再赌设备端部署结果。
     */
    @Volatile var verified = false
        private set

    @Volatile var deploying = false
        private set

    /** Rime 被自动停用的原因（null = 未停用），供设置页显示 */
    @Volatile var disableReason: String? = null
        private set

    private val lock = Any()
    private val readyListeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var ensureStartedFlag = false

    fun addReadyListener(l: () -> Unit) { readyListeners.add(l) }
    fun removeReadyListener(l: () -> Unit) { readyListeners.remove(l) }

    /** 用户在设置页「清除崩溃记录」时重置安全机制（下次启动重新尝试启用 Rime） */
    fun resetSafety(context: Context) {
        try {
            context.applicationContext.getSharedPreferences("rime_native", 0).edit().clear().apply()
            CrashLogger.nativeCrashFlagFile(context).delete()
        } catch (_: Exception) {}
        disableReason = null
    }

    /** 启动引擎（幂等，立即返回，加载在后台进行） */
    fun ensureStarted(context: Context) {
        if (ensureStartedFlag) return
        synchronized(this) {
            if (ensureStartedFlag) return
            ensureStartedFlag = true
        }
        val app = context.applicationContext
        Thread { try { boot(app) } catch (_: Exception) { } }.start()
    }

    private fun boot(app: Context) {
        val flag = CrashLogger.nativeCrashFlagFile(app)
        val prefs = app.getSharedPreferences("rime_native", 0)

        // 已持久停用（历史上 native 连续崩溃过）：不再碰任何 native 代码
        if (prefs.getBoolean("disabled", false)) {
            disableReason = "Rime 已因反复崩溃自动停用（清除崩溃记录后可重试）"
            ready = true
            for (l in readyListeners) { try { l() } catch (_: Exception) {} }
            return
        }

        // native 崩溃检测：native 调用前落标记文件、正常退出删除；
        // 启动时发现残留 = 上次进程死于 native 调用（SIGSEGV 抓不到 Java 异常）。
        // 连续 2 次即持久停用 Rime，只用内置引擎——宁可没有增强，不能反复闪退。
        if (flag.exists()) {
            val c = prefs.getInt("native_crash", 0) + 1
            prefs.edit().putInt("native_crash", c).apply()
            if (c >= 2) {
                prefs.edit().putBoolean("disabled", true).apply()
                disableReason = "检测到 native 崩溃（连续 $c 次），已自动停用 Rime 增强引擎"
                flag.delete()
                ready = true
                for (l in readyListeners) { try { l() } catch (_: Exception) {} }
                return
            }
        }

        // Java 层连续崩溃（CrashLogger 计数）→ 安全模式：跳过全部 native 初始化
        if (CrashLogger.safeMode()) {
            disableReason = "连续异常退出，已自动进入安全模式（内置引擎）"
            ready = true
            for (l in readyListeners) { try { l() } catch (_: Exception) {} }
            return
        }

        val shared = File(app.filesDir, "rime/shared")
        val user = File(app.filesDir, "rime/user")
        val stamp = File(shared, ".data_version")
        val needCopy = try {
            !stamp.exists() || stamp.readText().trim() != DATA_VER
        } catch (_: Exception) { true }

        if (needCopy) {
            shared.deleteRecursively()
            user.deleteRecursively()   // 升级时连用户数据目录一起清（旧版本残留状态会干扰新数据）
            shared.mkdirs()
            user.mkdirs()
            copyAssets(app, "rime", shared)
            // 词库已在构建期预编译（assets/rime/build 下的 .bin 产物）。把源文件时间戳改到极旧、
            // 编译产物改到最新，阻止 librime 运行时再现场编译（手表上编译 55 万词要分钟级）。
            markPrebuilt(shared)
            try { stamp.writeText(DATA_VER) } catch (_: Exception) {}
        } else {
            user.mkdirs()
        }

        val versionName = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }

        // 直接加载预编译产物：首次启动仅重建毫秒级的 prism（拼写表），
        // 18MB 词表直接复用；之后启动连维护都跳过（user.yaml 记录 last_build_time）。
        // 注意不要二次调用 startupRime——native 重复初始化会直接崩溃。
        try {
            flag.writeText("in-native")   // 从 loadLibrary 到自检结束的全程标记
            synchronized(lock) {
                Rime.startupRime(shared.absolutePath, user.absolutePath, versionName, false)
            }
        } catch (_: Throwable) { }

        // 自检轮询：trime 的 startupRime 是异步维护，立即检测可能过早。
        // 按下真实按键，确认「有组合串 + 有候选」才算引擎真正可用。
        // 轮询失败超时也不影响键盘——内置词库引擎始终可用，此引擎只作增强。
        val deadline = System.currentTimeMillis() + 45_000L
        while (System.currentTimeMillis() < deadline) {
            if (selfTest()) {
                verified = true
                break
            }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
        }

        // 正常走完（无论自检是否通过）：清 native 崩溃嫌疑与标记
        try { prefs.edit().putInt("native_crash", 0).apply() } catch (_: Exception) {}
        try { flag.delete() } catch (_: Exception) {}

        ready = true
        for (l in readyListeners) {
            try { l() } catch (_: Exception) {}
        }
    }

    /** 引擎可用性自检：按 'n' → 组合串非空且候选非空（成功后清空组合） */
    private fun selfTest(): Boolean = try {
        synchronized(lock) {
            try { Rime.clearRimeComposition() } catch (_: Throwable) {}
            val ok = Rime.processRimeKey('n'.code, 0)
            val raw = if (ok) Rime.getRimeRawInput() else ""
            val cands = if (ok) Rime.getRimeCandidates(0, 8) else emptyArray()
            try { Rime.clearRimeComposition() } catch (_: Throwable) {}
            ok && raw.isNotEmpty() && cands.isNotEmpty()
        }
    } catch (_: Throwable) { false }

    /**
     * 让 librime 认为“编译产物已是最新”：源文件（schema/dict/yaml）时间戳设到极旧，
     * build/ 下的预编译产物设到当前时间。这样 startupRime(fullCheck=false) 的增量维护
     * 不会触发重编译。
     */
    private fun markPrebuilt(shared: File) {
        val build = File(shared, "build").absolutePath + File.separator
        val now = System.currentTimeMillis()
        shared.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            if (f.absolutePath.startsWith(build)) f.setLastModified(now)
            else f.setLastModified(1000L)
        }
    }

    private fun copyAssets(app: Context, path: String, to: File) {
        val am = app.assets
        val names = am.list(path) ?: return
        if (names.isEmpty()) {
            to.parentFile?.mkdirs()
            am.open(path).use { inp -> FileOutputStream(to).use { inp.copyTo(it) } }
            return
        }
        to.mkdirs()
        for (n in names) copyAssets(app, "$path/$n", File(to, n))
    }

    // ==================== 主线程 API（未 ready 返回 null） ====================

    /** 按键（字母传小写字符 code）。返回快照；未处理/未就绪返回 null */
    fun processKey(keycode: Int): Snapshot? {
        if (!ready) return null
        synchronized(lock) {
            return try {
                if (Rime.processRimeKey(keycode, 0)) snapshot()
                else null
            } catch (_: Throwable) { null }
        }
    }

    /** 全局序号选候选，返回快照（commit 携带上屏文本） */
    fun selectCandidate(globalIndex: Int): Snapshot? {
        if (!ready) return null
        synchronized(lock) {
            return try {
                if (Rime.selectRimeCandidate(globalIndex, true)) snapshot()
                else null
            } catch (_: Throwable) { null }
        }
    }

    /** 清空组合 */
    fun clearComposition() {
        if (!ready) return
        synchronized(lock) {
            try { Rime.clearRimeComposition() } catch (_: Throwable) {}
        }
    }

    /** 当前原始输入（无组合时为空串） */
    fun rawInput(): String {
        if (!ready) return ""
        synchronized(lock) {
            return try { Rime.getRimeRawInput() } catch (_: Throwable) { "" }
        }
    }

    private fun snapshot(): Snapshot {
        val commit = try { Rime.getRimeCommit().text } catch (_: Throwable) { null }
        val raw = try { Rime.getRimeRawInput() } catch (_: Throwable) { "" }
        val cands = try {
            Rime.getRimeCandidates(0, 48).map { it.text }
        } catch (_: Throwable) { emptyList() }
        val preview = try {
            Rime.getRimeContext().composition.commitTextPreview
        } catch (_: Throwable) { null }
        return Snapshot(commit?.takeIf { it.isNotEmpty() }, raw, cands, preview)
    }
}
