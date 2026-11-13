package com.example.roundkb

import android.content.Context
import android.os.Build
import com.osfans.trime.core.Rime
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * librime 引擎管理（native 库来自同文输入法 trime v3.3.13）。
 *
 * 生命周期：[ensureStarted] 在后台线程同步 assets → 启动 Rime →（数据有更新时）
 * 等待部署完成 → 置 ready。主线程所有 API 调用经 [lock] 串行化，未 ready 时返回
 * null/空，由调用方回退到内置 WordStore 引擎。
 */
object RimeEngine {

    /** assets/rime 数据版本：改了 schema 或词库后 +1，触发重新复制与全量部署 */
    private const val DATA_VER = "1"

    /** RimeMessage.MessageType.Deploy 的 ordinal（native 回调 type 号） */
    private const val MSG_DEPLOY = 3

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
    @Volatile var deploying = false
        private set

    private val lock = Any()
    private val readyListeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var ensureStartedFlag = false

    fun addReadyListener(l: () -> Unit) { readyListeners.add(l) }
    fun removeReadyListener(l: () -> Unit) { readyListeners.remove(l) }

    /** 启动引擎（幂等，立即返回，部署在后台进行） */
    fun ensureStarted(context: Context) {
        if (ensureStartedFlag) return
        synchronized(this) {
            if (ensureStartedFlag) return
            ensureStartedFlag = true
        }
        val app = context.applicationContext
        Thread {
            try { boot(app) } catch (_: Exception) { }
        }.start()
    }

    private fun boot(app: Context) {
        val shared = File(app.filesDir, "rime/shared")
        val user = File(app.filesDir, "rime/user")
        val stamp = File(shared, ".data_version")
        val needDeploy = try {
            !stamp.exists() || stamp.readText().trim() != DATA_VER
        } catch (_: Exception) { true }

        if (needDeploy) {
            shared.deleteRecursively()
            shared.mkdirs()
            user.mkdirs()
            copyAssets(app, "rime", shared)
            try { stamp.writeText(DATA_VER) } catch (_: Exception) {}
        } else {
            user.mkdirs()
        }

        val versionName = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }

        val latch = CountDownLatch(1)
        val listener = { type: Int, params: Array<Any> ->
            if (type == MSG_DEPLOY && params.isNotEmpty()) {
                val s = params[0].toString()
                if (s.contains("Success") || s.contains("Failure")) latch.countDown()
            }
        }
        if (needDeploy) {
            deploying = true
            Rime.addMessageListener(listener)
        }
        try {
            synchronized(lock) {
                Rime.startupRime(shared.absolutePath, user.absolutePath, versionName, needDeploy)
            }
            if (needDeploy) {
                // 首次部署（约 55 万词条）在表壳上可能要一两分钟，等部署完成消息
                latch.await(300L, TimeUnit.SECONDS)
            }
        } finally {
            if (needDeploy) Rime.removeMessageListener(listener)
        }
        deploying = false
        ready = true
        for (l in readyListeners) {
            try { l() } catch (_: Exception) {}
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
