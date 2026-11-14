package com.example.roundkb

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.roundkb.R as AppR
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 圆屏 26 键键盘视图 —— Gboard for Wear OS 风格（Kotlin / v1.2）。
 *
 * 布局（Gboard for Wear OS 式**直排**）：
 *  - 三排按键为水平直线（不做弧形弯曲），每排宽度按「该行高度处的圆弧弦长」截取，
 *    两端自然落在圆内，整体贴合 Gboard 表盘键盘的观感；
 *  - 底部一条「宽胶囊空格」，两侧 123 / ⏎ 胶囊功能键（回车 Gboard 蓝）；
 *  - 候选词沿顶部横排，首选高亮；
 *  - 双重边距限制：外边距 + 越出安全圆自动径向拉回。
 *
 * 输入能力：
 *  - 组合输入 + 词库候选 + 1 次编辑纠错（漏按 / 错按 / 插入修正）；
 *  - 滑行手势输入（不要求轨迹逐字母命中）：
 *      ① 首尾字母一致过滤；② 词字母序列须为轨迹字母序列的**子序列**（允许路过别的键）；
 *      ③ 理想路径与轨迹的**双向 Chamfer 距离**（覆盖 + 蜿蜒惩罚）；
 *      ④ 词的每个字母键都被轨迹经过（键心到轨迹最小距离均值）；
 *      ⑤ 词频加成。综合打分取最优 3 个；
 *  - 未导入词库时字母直通上屏。
 */
class RoundKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface KeyListener {
        fun onText(text: String)
        fun onAction(action: Int)

        /** 当前光标位置（相对文本末尾 4096 字窗口的偏移；滑动空格移光标用） */
        fun cursorPos(): Int = 0

        /** 把光标移到绝对位置 pos（滑动空格移光标用） */
        fun onCursorSet(pos: Int) {}
    }

    companion object {
        const val ACTION_SHIFT = 1
        const val ACTION_DELETE = 2
        const val ACTION_ENTER = 3
        const val ACTION_MODE = 4
        const val ACTION_IME_PICKER = 5   // ‹ 切换输入法
        const val ACTION_HIDE = 6         // ✕ 收起键盘
        const val ACTION_EMOJI = 7        // 😀 表情面板
        const val ACTION_SYMPAGE = 8      // =\< 符号翻页
        const val ACTION_LETTER = 9       // ABC 回字母层
        const val ACTION_EMOJI_TAB = 10   // 表情分类页签（candIndex 存分类号）
        const val ACTION_MIC = 14         // 工具条麦克风：语音识别后上屏
        const val ACTION_CAND_PREV = 11   // 候选上一页 ‹
        const val ACTION_CAND_NEXT = 12   // 候选下一页 ›
        const val ACTION_ENG = 13         // 中（退出英文模式）

        // 键盘模式
        const val MODE_LETTER = 0
        const val MODE_SYMBOL = 1
        const val MODE_EMOJI = 2

        // ---- 几何参数（按表盘大小微调） ----
        private const val MARGIN_FRACTION = 0.05f    // 外边距占直径比例
        private const val MIN_MARGIN_PX = 10f        // 边距下限
        private const val SAFE_FACTOR = 0.98f        // 安全圆系数（越界自动拉回）
        private const val HIT_SLOP_MUL = 1.15f       // 触点容错
        private const val HIT_SLOP_ADD = 8f
        private const val GESTURE_START = 0.30f      // 起点位移 > 0.30R 进入滑行
        private const val GESTURE_MIN_PATH = 0.40f   // 轨迹最短长度（相对安全半径，短词也要能滑）
        private const val MAX_CANDIDATES = 3         // 滑行候选数

        // ---- Gboard 深色配色 ----
        private const val COLOR_BG = 0xF2101113.toInt()
        private const val COLOR_KEY = 0xFF313338.toInt()
        private const val COLOR_KEY_CTL = 0xFF3C4043.toInt()
        private const val COLOR_KEY_SPACE = 0xFF26282B.toInt()
        private const val COLOR_ACCENT = 0xFF8AB4F8.toInt()
        private const val COLOR_PRESSED = 0xFF5F6368.toInt()
        private const val COLOR_TEXT = Color.WHITE
        private const val COLOR_TEXT_DARK = 0xFF1F2124.toInt()

        private val ROW0 = "QWERTYUIOP".map { it.toString() }
        private val ROW1 = "ASDFGHJKL".map { it.toString() }
        private val ROW2 = "ZXCVBNM".map { it.toString() }

        /** 符号两页（?123 / =\<），每页三行 —— 完整覆盖标点/括号/货币/数学符号 */
        private val SYM_PAGES = arrayOf(
            arrayOf(
                "1234567890".map { it.toString() },
                listOf("@", "#", "￥", "%", "&", "*", "-", "+", "/", "("),
                listOf(".", ",", "?", "!", "'", "\"", ":", ";", ")")
            ),
            arrayOf(
                listOf("~", "`", "^", "°", "=", "<", ">", "±", "×", "÷"),
                listOf("[", "]", "{", "}", "|", "\\", "_", "—", "…", "·"),
                listOf("€", "£", "$", "₩", "¡", "¿", "«", "»", "§")
            )
        )

        /** 行宽占该高度处圆弧弦长的比例（直排下控制左右边距） */
        private const val ROW_SPAN = 0.90f
        private const val CAND_PER_PAGE = 3   // 候选行每页芯片数
        private const val CAND_POOL = 9    // 候选池上限（翻页取用）
        private const val LONG_PRESS_MS = 550L
    }

    private class Key {
        var label: String = ""
        var text: String? = null
        var action: Int = -1
        var candIndex: Int = -1
        var x = 0f; var y = 0f
        var hw = 0f; var hh = 0f   // 圆角矩形半宽/半高
        var control = false
        /** 无键帽（Gboard Wear 式透明字母键 / ⇧ / ⌫）：平时只画内容不画底 */
        var noFill = false
        /** 表情键：以较大字号绘制，点按直接上屏 */
        var emoji = false
        /** Material Icons 矢量资源 id（非 0 时绘制图标而非文字） */
        var iconRes: Int = 0
        /** 描边键帽（Gboard Wear 工具条式：圆角矩形描边，不填充） */
        var chip = false
        /** false = 置灰不可点（候选翻页到边界等） */
        var enabled = true
        /** 键帽下部小字（九键：数字下方的字母组） */
        var subLabel: String? = null
        /** 键代表的字母集合（双键 "ab"；滑行解码与双键选字母用） */
        var chars: String? = null
    }

    private val keys = mutableListOf<Key>()

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()
    private val trailPath = Path()

    var keyListener: KeyListener? = null

    private var pressed: Key? = null
    private var mode = MODE_LETTER          // 字母 / 符号 / 表情
    private var symPage = 0                 // 符号页（0=?123  1==\<）
    private var engMode = false             // 英文直输模式（长按 ?123 进入）
    private var candPage = 0                // 候选翻页
    private var engLongFired = false
    private val engLongPress = Runnable {
        if (pressed?.action == ACTION_MODE && mode == MODE_LETTER && !engMode) {
            engMode = true
            engLongFired = true
            pressed = null
            SettingsStore.keyVibrate(context, strong = true)
            relayout()
        }
    }
    private var emojiCat = 0                // 表情分类（0=最近）
    private var emojiPage = 0               // 表情分类内页码
    private val recentEmoji = mutableListOf<String>()   // 最近使用的表情（内存）
    private var emojiSwiped = false         // 表情面板横滑翻页
    // 分类页签条：加大尺寸 + 横向滑动 + 吸附对中
    private var emojiTabScroll = 0f         // 页签条滑动偏移（px，正 = 页签右移）
    private var emojiTabSpacing = 0f        // 页签间距（layoutKeys 时更新）
    private var emojiTabMaxOff = 0f         // 滑动偏移上限（px）
    private var emojiTabY = 0f              // 页签条中心 y（绝对坐标）
    private var emojiTabHh = 0f             // 页签半高
    private var tabTouch = false            // 手指落在页签条带内
    private var tabDragging = false         // 正在拖动页签条
    private var tabScrollAtDragStart = 0f
    private var emojiTabSnapPending = true  // 下次布局时把当前分类页签吸附对中
    private var shift = false
    private var capsLock = false
    private var lastShiftTap = 0L
    /** 最近一次抬指的 x（双键左右半区选字母用） */
    private var lastTapX = 0f

    // 空格横向滑动 = 移动光标
    private var cursorSlide = false
    private var cursorBase = 0      // 拖动起点光标位置
    private var cursorLast = 0      // 已同步到输入框的光标位置

    private val buf = StringBuilder()
    private val candidates = mutableListOf<String>()
    private val store: WordStore = WordStore.get(context)

    /** librime 组合中（原始串非空）。候选与组合串均来自 rime 快照 */
    private var rimeComposing = false

    // 滑行手势状态
    private val trace = mutableListOf<FloatArray>()
    private var tracing = false
    /** 滑行解码器（FlorisBoard / SHARK² 算法，布局变化时重建） */
    private var decoder: GlideDecoder? = null

    private val deleteRepeat = object : Runnable {
        override fun run() {
            handleDelete()
            postDelayed(this, 60)
        }
    }

    init {
        textPaint.color = COLOR_TEXT
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textPaint.textAlign = Paint.Align.CENTER
        trailPaint.color = COLOR_ACCENT
        trailPaint.style = Paint.Style.STROKE
        trailPaint.strokeWidth = 7f
        trailPaint.strokeCap = Paint.Cap.ROUND
        trailPaint.strokeJoin = Paint.Join.ROUND
        trailPaint.alpha = 210
        isHapticFeedbackEnabled = true

        // librime：幂等启动（部署在后台），就绪后刷新候选显示
        RimeEngine.ensureStarted(context)
        RimeEngine.addReadyListener {
            post { invalidate() }
        }
    }

    /** 26 键 / 双键中文模式且 librime 自检通过 → 走 rime 组合（否则内置词库引擎，九键始终内置） */
    private fun rimeMode(): Boolean =
        mode == MODE_LETTER && !engMode &&
            SettingsStore.keyLayout(context) != SettingsStore.LAYOUT_9 && RimeEngine.verified

    /** 清掉本地的 rime 组合状态（不提交） */
    private fun clearRimeLocal() {
        if (rimeComposing) {
            RimeEngine.clearComposition()
            rimeComposing = false
        }
    }

    /** 用 rime 快照同步组合串与候选 */
    private fun applyRimeSnapshot(snap: RimeEngine.Snapshot) {
        buf.setLength(0)
        buf.append(snap.rawInput)
        candidates.clear()
        candidates.addAll(snap.candidates.take(CAND_POOL))
        candPage = 0
        rimeComposing = snap.rawInput.isNotEmpty()
        layoutKeys(width.toFloat(), height.toFloat())
        invalidate()
    }

    /** 字母逐字符喂给 rime；rime 要求上屏的文本先走 onText */
    private fun feedRimeText(t: String) {
        for (c in t.lowercase()) {
            if (!c.isLetter()) continue
            val snap = RimeEngine.processKey(c.code)
            if (snap == null) {          // 未接管（未就绪/异常）→ 直通上屏
                keyListener?.onText(c.toString())
                continue
            }
            snap.commit?.let { keyListener?.onText(it) }
            applyRimeSnapshot(snap)
        }
    }

    // ============================== 布局 ==============================

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutKeys(w.toFloat(), h.toFloat())
    }

    private fun relayout() {
        shift = false
        capsLock = false
        pressed = null
        buf.setLength(0)
        candidates.clear()
        removeCallbacks(deleteRepeat)
        layoutKeys(width.toFloat(), height.toFloat())
        invalidate()
    }

    /** 设置（按键大小等）变更后由 IME 调用，下次弹出键盘即生效 */
    fun refreshLayout() = relayout()

    private fun safeRadius(size: Float): Float = size / 2f - max(MIN_MARGIN_PX, size * MARGIN_FRACTION)

    private fun layoutKeys(w: Float, h: Float) {
        keys.clear()
        if (w <= 0 || h <= 0) return

        val size = min(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val R = safeRadius(size)

        val candMode = buf.isNotEmpty() && candidates.isNotEmpty() && mode == MODE_LETTER
        val s = SettingsStore.keyScale(context)

        // Gboard Wear 真机版式（对照实机截图）：
        //  - 顶部工具条：‹ 切换输入法 / 😀 表情 / ?123 符号 / ↵ 回车 / ✕ 收起（蓝圆），
        //    字母层组合输入时该位置替换为候选词条；
        //  - 字母层：三行小写字母键，无键帽；
        //  - 符号层：两页完整符号（?123 / =\<），保留键帽便于辨认；
        //  - 表情层：10 分类页签 + 5×4 网格 + 横滑翻页 + 页码圆点；
        //  - 底排：⇧（或 =\< / ABC）/ 宽空格胶囊 / ⌫。
        val dTop = 0.30f * R
        val chordTop = sqrt(max(0f, R * R - dTop * dTop))
        val unit = 2f * chordTop * ROW_SPAN / 10f * s

        if (candMode) {
            addCandidateRow(-0.60f, cx, cy, R)
        } else if (mode != MODE_EMOJI) {
            addToolbar(-0.60f, cx, cy, R, s)   // 表情层由页签行取代工具条
        }
        when (mode) {
            MODE_LETTER -> when (SettingsStore.keyLayout(context)) {
                SettingsStore.LAYOUT_9 -> addT9Grid(cx, cy, R, s)
                SettingsStore.LAYOUT_DUAL -> addDualGrid(cx, cy, R, s)
                else -> {
                    addRow(ROW0, -0.30f, cx, cy, R, unit, bare = true)
                    addRow(ROW1, -0.02f, cx, cy, R, unit, bare = true)
                    addRow(ROW2, 0.26f, cx, cy, R, unit, bare = true)
                }
            }
            MODE_SYMBOL -> {
                val page = SYM_PAGES[symPage]
                addRow(page[0], -0.30f, cx, cy, R, unit, bare = false)
                addRow(page[1], -0.02f, cx, cy, R, unit, bare = false)
                addRow(page[2], 0.26f, cx, cy, R, unit, bare = false)
            }
            MODE_EMOJI -> addEmojiGrid(cx, cy, R, s)
        }
        addBottomRow(0.54f, cx, cy, R, s)

        clampToSafeCircle(cx, cy, R)
        rebuildDecoder()
    }

    /**
     * 重建滑行解码器：只做轻量装配（端点索引在 WordStore 里已建好，
     * 与布局无关且只建一次），因此候选行出现/消失导致的重排也不会卡顿。
     */
    private fun rebuildDecoder() {
        val letterKeys = ArrayList<GlideKey>(26)
        val seen = HashSet<Char>(32)
        for (k in keys) {
            if (k.control || k.candIndex >= 0) continue
            if (k.chars != null) {
                // 双键：一个键注册两个字母（滑行时共用键位几何）
                for (c in k.chars!!) {
                    val ch = c.lowercaseChar()
                    if (!ch.isLetter() || !seen.add(ch)) continue
                    letterKeys.add(GlideKey(ch, k.x, k.y, k.hw * 2f, k.hh * 2f))
                }
                continue
            }
            val t = k.text ?: continue
            if (t.length != 1) continue
            val ch = t[0].lowercaseChar()
            if (!ch.isLetter() || !seen.add(ch)) continue
            letterKeys.add(GlideKey(ch, k.x, k.y, k.hw * 2f, k.hh * 2f))
        }
        val d = GlideDecoder(store.endpointIndex(), letterKeys, store.maxFrequency)
        d.setMinPointDistance((letterKeys.firstOrNull()?.w?.div(4f)) ?: 12f)
        decoder = d
    }

    /** 顶部工具条（Gboard Wear 真机式）：‹ / 😀 / ?123 / 🎤 均为描边键帽，✕ 蓝色实心圆 */
    private fun addToolbar(y0f: Float, cx: Float, cy: Float, R: Float, s: Float) {
        val y = cy + y0f * R
        val hw = 0.098f * R * s
        val hh = 0.085f * R * s
        addKey("‹", null, ACTION_IME_PICKER, cx - 0.46f * R, y, hw, hh, true).apply {
            chip = true
            iconRes = AppR.drawable.ic_chevron_left
        }
        addKey("", null, ACTION_EMOJI, cx - 0.23f * R, y, hw, hh, true).apply {
            chip = true
            iconRes = AppR.drawable.ic_mood
        }
        addKey(if (engMode) "EN" else if (mode == MODE_SYMBOL) "ABC" else "?123",
               null, ACTION_MODE, cx, y, hw, hh, true).apply {
            chip = true
        }
        addKey("", null, ACTION_MIC, cx + 0.23f * R, y, hw, hh, true).apply {
            chip = true
            iconRes = AppR.drawable.ic_mic
        }
        addKey("✕", null, ACTION_HIDE, cx + 0.46f * R, y, hw, hh, true).apply {
            iconRes = AppR.drawable.ic_close
        }
    }

    /** 当前是否处于九键 T9 中文模式（字母层 + 九键布局 + 有词库非英文） */
    private fun t9Mode(): Boolean =
        mode == MODE_LETTER && !engMode &&
            SettingsStore.keyLayout(context) == SettingsStore.LAYOUT_9 && store.hasDictionary

    /** 九键布局：3×3 数字键（2-9 标字母组，1 为句读键），点按进 T9 拼音解码 */
    private fun addT9Grid(cx: Float, cy: Float, R: Float, s: Float) {
        val su = s.coerceIn(0.9f, 1.1f)
        val unit = 0.325f * R * su
        val hw = unit * 0.46f
        val hh = 0.115f * R * su
        val ys = listOf(-0.29f, -0.02f, 0.26f)
        val subs = arrayOf("。？！", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")
        for (idx in 1..9) {
            val row = (idx - 1) / 3
            val col = (idx - 1) % 3
            val k = addKey(idx.toString(), idx.toString(), -1,
                cx + (col - 1) * unit, cy + ys[row] * R, hw, hh, true)
            k.subLabel = subs[idx - 1]
            k.chars = null
        }
    }

    /** 双键布局：13 个键各含两个字母（左半/右半区点按选字母），组合引擎与 26 键共用 */
    private fun addDualGrid(cx: Float, cy: Float, R: Float, s: Float) {
        val su = s.coerceIn(0.85f, 1.15f)
        val dTop = 0.30f * R
        val chordTop = sqrt(max(0f, R * R - dTop * dTop))
        val unit = min(2f * chordTop * ROW_SPAN / 5f * su, 0.37f * R)
        val hw = unit * 0.46f
        val hh = 0.122f * R * su
        val rows = arrayOf("ab,cd,ef,gh,ij", "kl,mn,op,qr,st", "uv,wx,yz")
        val ys = listOf(-0.30f, -0.02f, 0.26f)
        rows.forEachIndexed { r, row ->
            val items = row.split(",")
            items.forEachIndexed { i, pair ->
                val x = cx + (i - (items.size - 1) / 2f) * unit
                val k = addKey(pair, null, -1, x, cy + ys[r] * R, hw, hh, false)
                k.noFill = true
                k.chars = pair
            }
        }
    }

    /** 表情层：分类页签条（加大、可横向滑动、吸附对中、高亮当前分类）+ 5×4 网格（横滑翻页） */
    private fun addEmojiGrid(cx: Float, cy: Float, R: Float, s: Float) {
        // 分类页签条：Material 图标页签，横向滑动（视野外的页签不生成）
        val tabN = EmojiData.names.size
        val tabSpacing = 0.158f * R
        val tabY = cy - 0.575f * R
        val tabHw = 0.072f * R
        val tabHh = 0.072f * R
        val chordTab = sqrt(max(0f, R * R - 0.575f * R * 0.575f * R))
        val halfSpan = chordTab * 0.80f - tabHw     // 页签中心允许偏离中心的半宽
        emojiTabMaxOff = max(0f, (tabN - 1) / 2f * tabSpacing - halfSpan)
        emojiTabSpacing = tabSpacing
        emojiTabY = tabY
        emojiTabHh = tabHh
        emojiTabScroll = emojiTabScroll.coerceIn(-emojiTabMaxOff, emojiTabMaxOff)
        // 打开面板 / 切换分类后：把当前分类页签吸附对中（用户拖动时不干预）
        if (emojiTabSnapPending && !tabDragging) {
            emojiTabSnapPending = false
            emojiTabScroll = (-(emojiCat - (tabN - 1) / 2f) * tabSpacing)
                .coerceIn(-emojiTabMaxOff, emojiTabMaxOff)
        }
        for (i in 0 until tabN) {
            val x = cx + (i - (tabN - 1) / 2f) * tabSpacing + emojiTabScroll
            if (abs(x - cx) > halfSpan) continue     // 滑出视野的页签不参与命中
            addKey("", null, ACTION_EMOJI_TAB, x, tabY, tabHw, tabHh, true).apply {
                iconRes = EmojiData.tabIcons[i]
                candIndex = i
                if (i == emojiCat) noFill = false else noFill = true
            }
        }

        // 表情网格
        val list = currentEmojiList()
        if (list.isEmpty()) return
        val perPage = 20
        val maxPage = (list.size + perPage - 1) / perPage
        if (emojiPage >= maxPage) emojiPage = maxPage - 1
        val items = list.drop(emojiPage * perPage).take(perPage)
        val dTop = 0.33f * R
        val chord = sqrt(max(0f, R * R - dTop * dTop))
        // 横向步距设上限：按键放大（keyScale 大）时也不越出圆屏
        val unitE = min(2f * chord * 0.92f / 5f * s, 0.37f * R)
        val ys = listOf(-0.33f, -0.13f, 0.07f, 0.27f)
        val hw = unitE * 0.48f
        val hh = 0.105f * R * s
        items.forEachIndexed { idx, e ->
            val row = idx / 5
            val col = idx % 5
            val x = cx + (col - 2f) * unitE
            val y = cy + ys[row] * R
            addKey(e, e, -1, x, y, hw, hh, true).apply {
                emoji = true
                noFill = true
            }
        }
    }

    private fun currentEmojiList(): List<String> =
        if (emojiCat == 0) recentEmoji else EmojiData.categories[emojiCat - 1]

    /** 底排：⇧（或 =\< / ABC）/ 宽空格胶囊 / ⌫ —— Gboard Wear 式三键底排 */
    private fun addBottomRow(y0f: Float, cx: Float, cy: Float, R: Float, s: Float) {
        val y = cy + y0f * R
        val hw = 0.115f * R * s
        val hh = 0.098f * R * s
        when (mode) {
            MODE_LETTER -> if (engMode) {
                addKey("中", null, ACTION_ENG, cx - 0.40f * R, y, hw, hh, true)
            } else {
                addKey("⇧", null, ACTION_SHIFT, cx - 0.40f * R, y, hw, hh, true).apply {
                    noFill = true
                    iconRes = AppR.drawable.ic_capslock
                }
            }
            MODE_SYMBOL -> addKey(if (symPage == 0) "=\\<" else "?123", null, ACTION_SYMPAGE,
                cx - 0.40f * R, y, hw, hh, true)
            else -> addKey("ABC", null, ACTION_LETTER, cx - 0.40f * R, y, hw, hh, true)
        }
        if (mode != MODE_EMOJI) {
            // Gboard Wear 式：中文拼音层胶囊显示「拼」（组合时改为显示组合串），符号层为空格
            if (mode == MODE_LETTER) {
                addKey(if (engMode) "英" else "拼", " ", -1, cx, y, 0.26f * R * s, 0.105f * R * s, true)
            } else {
                addKey("␣", " ", -1, cx, y, 0.26f * R * s, 0.105f * R * s, true).apply {
                    iconRes = AppR.drawable.ic_space
                }
            }
        }
        addKey("⌫", null, ACTION_DELETE, cx + 0.40f * R, y, hw, hh, true).apply {
            noFill = mode != MODE_SYMBOL
            iconRes = AppR.drawable.ic_backspace
        }
    }

    /**
     * 水平直排一行按键（Gboard 式：等宽键，行居中，行宽随键数递减）。
     * @param y0f  行中心纵向偏移（相对 R，负 = 上方）
     * @param unit 等宽键步距（键心间距），由 layoutKeys 按最宽行统一确定
     */
    private fun addRow(items: List<String>, y0f: Float, cx: Float, cy: Float, R: Float, unit: Float,
                       bare: Boolean) {
        val y0 = cy + y0f * R
        val n = items.size
        if (n == 0 || unit <= 0f) return

        val hw = unit * 0.48f          // 命中区半宽（步距的 96%）
        val hh = unit * 0.55f          // 命中区半高

        for ((i, s) in items.withIndex()) {
            val x = cx + (i - (n - 1) / 2f) * unit

            when (s) {
                "ABC", "123" -> addKey(s, null, ACTION_MODE, x, y0, hw, hh, true)
                else -> {
                    // 字母/符号键：字母层小写无键帽（Gboard Wear 式），shift 时经绘制层转大写
                    addKey(s.lowercase(), s.lowercase(), -1, x, y0, hw, hh, false).apply {
                        noFill = bare
                    }
                }
            }
        }
    }

    /** 候选词沿顶部弧排布（每页 3 个胶囊芯片，多页时两侧 ‹ › 翻页） */
    private fun addCandidateRow(y0f: Float, cx: Float, cy: Float, R: Float) {
        if (candidates.isEmpty()) return
        val y = cy + y0f * R
        val s = SettingsStore.keyScale(context)   // 候选胶囊随全局按键缩放
        val hh = 0.085f * R * s
        val spacing = 0.335f * R * s
        val maxPage = (candidates.size + CAND_PER_PAGE - 1) / CAND_PER_PAGE
        if (candPage >= maxPage) candPage = 0
        val start = candPage * CAND_PER_PAGE
        val pageItems = candidates.drop(start).take(CAND_PER_PAGE)
        pageItems.forEachIndexed { i, text ->
            val k = Key()
            k.label = text
            k.text = text
            k.candIndex = i                    // 页内位置；fireKey 换算全局下标
            k.x = cx + (i - (pageItems.size - 1) / 2f) * spacing
            k.y = y
            k.hw = 0.155f * R * s
            k.hh = hh
            k.control = true
            keys.add(k)
        }
        if (maxPage > 1) {
            val px = spacing + 0.10f * R * s
            val dim = candPage == 0
            addKey("‹", null, ACTION_CAND_PREV, cx - px, y,
                0.052f * R, 0.048f * R, true).apply {
                noFill = dim; enabled = !dim
            }
            val dim2 = candPage >= maxPage - 1
            addKey("›", null, ACTION_CAND_NEXT, cx + px, y,
                0.052f * R, 0.048f * R, true).apply {
                noFill = dim2; enabled = !dim2
            }
        }
    }

    private fun addKey(label: String, text: String?, action: Int,
                       x: Float, y: Float, hw: Float, hh: Float, control: Boolean): Key {
        val k = Key()
        k.label = label
        k.text = text
        k.action = action
        k.x = x
        k.y = y
        k.hw = hw
        k.hh = hh
        k.control = control
        keys.add(k)
        return k
    }

    /** 边距限制：按键外缘（外接圆角估算）越出「安全圆」就沿径向收回 */
    private fun clampToSafeCircle(cx: Float, cy: Float, R: Float) {
        val maxD = R * SAFE_FACTOR
        for (k in keys) {
            val outer = hypot(k.hw, k.hh) * 0.94f
            val dx = k.x - cx
            val dy = k.y - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d > 0 && d + outer > maxD) {
                val f = (maxD - outer) / d
                k.x = cx + dx * f
                k.y = cy + dy * f
            }
        }
    }

    // ============================== 纠错与候选 ==============================

    /** 前缀匹配 + 1 次编辑纠错（删除/替换/插入），词频优先 */
    private fun correctAndSuggest(p0: String, limit: Int): List<String> {
        val out = LinkedHashSet<String>()
        out.addAll(store.prefix(p0, limit))
        if (out.size >= limit || p0.length < 2) return out.toList()

        val variants = LinkedHashSet<String>()
        for (i in p0.indices) {                                     // 删除一位
            variants.add(p0.removeRange(i, i + 1))
            for (c in 'a'..'z') {                                   // 替换一位
                variants.add(p0.substring(0, i) + c + p0.substring(i + 1))
            }
        }
        for (i in 0..p0.length) {                                   // 插入一位
            for (c in 'a'..'z') {
                variants.add(p0.substring(0, i) + c + p0.substring(i))
            }
        }
        for (v in variants) {
            if (v == p0) continue
            out.addAll(store.prefix(v, max(1, limit - out.size)))
            if (out.size >= limit) break
        }
        return out.toList()
    }

    private fun updateCandidates() {
        candidates.clear()
        candPage = 0
        val p = buf.toString()
        if (p.isNotEmpty() && store.hasDictionary) {
            if (t9Mode()) {
                // 九键 T9：整句解码 + 词级候选（数字串 → 拼音展开受音节表约束）
                if (p.length >= 4) candidates.addAll(store.t9Sentence(p, 6))
                if (candidates.size < CAND_POOL) {
                    for (w in store.t9Candidates(p, CAND_POOL - candidates.size)) {
                        if (w !in candidates) candidates.add(w)
                        if (candidates.size >= CAND_POOL) break
                    }
                }
            } else {
                // 整句拼音优先：连续按键串 → 自动分词组句；不足再用词级前缀候选补齐
                if (p.length >= 4) candidates.addAll(store.sentence(p, 6))
                if (candidates.size < CAND_POOL) {
                    for (w in correctAndSuggest(p, CAND_POOL - candidates.size)) {
                        if (w !in candidates) candidates.add(w)
                        if (candidates.size >= CAND_POOL) break
                    }
                }
            }
        }
        layoutKeys(width.toFloat(), height.toFloat())
        invalidate()
    }

    // ============================== 滑行手势 ==============================

    private fun dist(a: FloatArray, b: FloatArray): Float = hypot(a[0] - b[0], a[1] - b[1])

    private fun pathLength(pts: List<FloatArray>): Float {
        var len = 0f
        for (i in 1 until pts.size) len += dist(pts[i - 1], pts[i])
        return len
    }

    /**
     * 滑行结束：交给 [GlideDecoder] 出候选。
     *
     * 解码走 FlorisBoard / SHARK² 流水线（端点剪枝 → 长度剪枝 → 形状+位置高斯 + 词频），
     * 不再把轨迹经过的字母序列当作输入内容。无命中时静默结束。
     */
    private fun finishGesture() {
        val d = decoder
        val size = min(width, height).toFloat()
        if (d == null || d.pathLength() < GESTURE_MIN_PATH * safeRadius(size)) return

        val cands = d.suggest(MAX_CANDIDATES)
        if (cands.isEmpty()) {
            SettingsStore.keyVibrate(context, strong = true)   // 无命中：强震动提示
            return
        }
        buf.setLength(0)
        candidates.clear()
        candidates.addAll(cands)
        layoutKeys(width.toFloat(), height.toFloat())
        invalidate()
        SettingsStore.keyVibrate(context)
    }

    // ============================== 绘制 ==============================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(COLOR_BG)

        // 滑行轨迹
        if (tracing && trace.size > 1) {
            trailPath.reset()
            trailPath.moveTo(trace[0][0], trace[0][1])
            for (i in 1 until trace.size) trailPath.lineTo(trace[i][0], trace[i][1])
            canvas.drawPath(trailPath, trailPaint)
        }

        for (k in keys) {
            var fill: Int
            var txt = COLOR_TEXT
            when {
                k === pressed -> fill = COLOR_PRESSED
                k.action == ACTION_EMOJI_TAB ->
                    if (k.candIndex == emojiCat) { fill = COLOR_ACCENT; txt = COLOR_TEXT_DARK }
                    else { fill = COLOR_KEY_CTL }
                k.candIndex == 0 -> { fill = COLOR_ACCENT; txt = COLOR_TEXT_DARK }  // 首选候选
                k.candIndex > 0 -> fill = COLOR_KEY_CTL
                k.action == ACTION_HIDE -> { fill = COLOR_ACCENT; txt = Color.WHITE }  // ✕ 蓝圆
                k.action == ACTION_EMOJI && mode == MODE_EMOJI ->
                    { fill = COLOR_ACCENT; txt = COLOR_TEXT_DARK }
                k.action == ACTION_ENTER -> { fill = COLOR_KEY_CTL }
                k.action == ACTION_SHIFT && (shift || capsLock) ->
                    { fill = COLOR_ACCENT; txt = COLOR_TEXT_DARK }
                k.action == ACTION_MODE && mode == MODE_SYMBOL ->
                    { fill = COLOR_ACCENT; txt = COLOR_TEXT_DARK }
                k.text == " " -> fill = COLOR_KEY_CTL
                k.control -> fill = COLOR_KEY_CTL
                else -> fill = COLOR_KEY
            }
            // 无键帽键（字母 / ⇧ / ⌫）：平时不画底，按下或激活时才高亮
            val bare = k.noFill && k !== pressed &&
                !(k.action == ACTION_SHIFT && (shift || capsLock))
            if (k.chip) {
                // Gboard Wear 工具条描边键帽：圆角矩形描边，按下时填充高亮
                rectF.set(k.x - k.hw, k.y - k.hh, k.x + k.hw, k.y + k.hh)
                if (k === pressed) {
                    keyPaint.style = Paint.Style.FILL
                    keyPaint.color = COLOR_PRESSED
                    canvas.drawRoundRect(rectF, k.hh * 0.5f, k.hh * 0.5f, keyPaint)
                }
                keyPaint.style = Paint.Style.STROKE
                keyPaint.strokeWidth = max(1.5f, k.hh * 0.10f)
                keyPaint.color = if (k === pressed) COLOR_TEXT else COLOR_PRESSED
                canvas.drawRoundRect(rectF, k.hh * 0.5f, k.hh * 0.5f, keyPaint)
                keyPaint.style = Paint.Style.FILL
            } else if (!bare) {
                keyPaint.color = fill
                if (k.action == ACTION_HIDE) {
                    canvas.drawCircle(k.x, k.y, k.hh, keyPaint)   // ✕ 蓝色正圆（Gboard 式）
                } else {
                    val corner = if (k.text == " ") k.hh else k.hh * 0.62f
                    canvas.drawRoundRect(rectF, corner, corner, keyPaint)
                }
            }

            // 图标键（Material Icons 矢量）：空格键有组合串时仍显示文字
            val showIcon = k.iconRes != 0 && !(k.text == " " && buf.isNotEmpty())
            if (showIcon) {
                val dr = context.getDrawable(k.iconRes)
                if (dr != null) {
                    dr.setTint(txt)
                    // 图标边长：描边键帽内 0.70×键高（避免撑满发灰）；普通键 0.95×短边；
                    // 均按设置页图标大小缩放，且不越出按键
                    val iconScale = SettingsStore.iconScale(context)
                    val s = if (k.chip) {
                        min(k.hh * 0.70f * iconScale, k.hw * 0.85f)
                    } else {
                        min(min(k.hw, k.hh) * 0.95f * iconScale, min(k.hw, k.hh) * 0.92f)
                    }
                    dr.setBounds(
                        (k.x - s).toInt(), (k.y - s).toInt(),
                        (k.x + s).toInt(), (k.y + s).toInt()
                    )
                    dr.draw(canvas)
                    if (k.action == ACTION_SHIFT && capsLock) {   // 大写锁定指示点
                        keyPaint.color = txt
                        canvas.drawCircle(k.x + k.hw * 0.55f, k.y - k.hh * 0.62f, k.hh * 0.10f, keyPaint)
                    }
                    continue
                }
            }

            var label = k.label
            if (k.text == " " && buf.isNotEmpty()) label = buf.toString()   // 组合串显示在空格键
            val t = k.text
            if (t != null && !k.control && k.candIndex < 0 && shift && mode == MODE_LETTER &&
                !capsLock && t.length == 1 && t[0].isLowerCase()
            ) {
                label = label.uppercase()
            }

            var size = when {
                k.emoji && k.action == ACTION_EMOJI -> k.hh * 1.5f        // 工具条 😀
                k.emoji && k.action == ACTION_EMOJI_TAB -> k.hh * 1.3f    // 分类页签
                k.emoji -> k.hh * 1.45f                                   // 表情网格
                k.chip && k.iconRes == 0 -> k.hh * 0.82f                  // 描边键帽内文字（?123/EN）
                k.noFill && k.iconRes == 0 -> k.hh * 1.35f                // 无键帽字母：加大加亮（Gboard 式）
                k.control -> k.hh * 1.2f
                else -> k.hh * 0.95f
            }
            if (label.length > 2) {
                val fit = k.hw * 1.8f / (0.60f * label.length)
                size = min(size, fit)
            }
            textPaint.textSize = size
            textPaint.color = txt
            val fm = textPaint.fontMetrics
            var ty = k.y - (fm.ascent + fm.descent) / 2f
            if (k.subLabel != null) ty -= k.hh * 0.16f      // 有下部小字：主体上移
            canvas.drawText(label, k.x, ty, textPaint)
            k.subLabel?.let { sub ->
                textPaint.textSize = size * 0.52f
                textPaint.color = 0xFF9AA0A6.toInt()
                val fm2 = textPaint.fontMetrics
                val ty2 = k.y + k.hh * 0.52f - (fm2.ascent + fm2.descent) / 2f
                canvas.drawText(sub, k.x, ty2, textPaint)
            }
        }

        // 空格滑动光标提示：空格胶囊两侧画小箭头
        if (cursorSlide) {
            keys.lastOrNull { it.text == " " }?.let { sk ->
                textPaint.textSize = sk.hh * 0.8f
                textPaint.color = COLOR_ACCENT
                val fm3 = textPaint.fontMetrics
                val ty3 = sk.y - (fm3.ascent + fm3.descent) / 2f
                canvas.drawText("‹", sk.x - sk.hw * 0.62f, ty3, textPaint)
                canvas.drawText("›", sk.x + sk.hw * 0.62f, ty3, textPaint)
            }
        }

        // 表情层附加绘制：当前分类名（页签条下方）+ 最近分类为空时的提示 + 页码圆点
        if (mode == MODE_EMOJI) {
            val size = min(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            val R = safeRadius(size)
            textPaint.textSize = 0.038f * R
            textPaint.color = 0xFF9AA0A6.toInt()
            val fm = textPaint.fontMetrics
            canvas.drawText(EmojiData.names[emojiCat], cx,
                cy - 0.472f * R - (fm.ascent + fm.descent) / 2f, textPaint)
            if (currentEmojiList().isEmpty()) {
                textPaint.textSize = 0.075f * R
                textPaint.color = 0xFF9AA0A6.toInt()
                val fm = textPaint.fontMetrics
                val ty = cy - (fm.ascent + fm.descent) / 2f
                canvas.drawText("暂无最近表情", cx, ty, textPaint)
            } else {
                val list = currentEmojiList()
                val maxPage = (list.size + 19) / 20
                keyPaint.color = COLOR_ACCENT
                // 页码圆点：网格底(0.375R)与底排顶(0.44R)之间的空隙
                for (i in 0 until maxPage) {
                    val dotX = cx + (i - (maxPage - 1) / 2f) * 0.055f * R
                    if (i != emojiPage) keyPaint.color = COLOR_PRESSED
                    canvas.drawCircle(dotX, cy + 0.41f * R, 0.010f * R, keyPaint)
                    keyPaint.color = COLOR_ACCENT
                }
            }
        }
    }

    // ============================== 触控 ==============================

    private fun nearestKey(x: Float, y: Float): Key? {
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        for (k in keys) {
            val dx = max(abs(x - k.x) - k.hw, 0f)
            val dy = max(abs(y - k.y) - k.hh, 0f)
            val d = hypot(dx, dy)
            if (d < bestD) { bestD = d; best = k }
        }
        val b = best
        return if (b != null && b.enabled && bestD <= b.hh * HIT_SLOP_MUL + HIT_SLOP_ADD) b else null
    }

    private fun updatePressed(x: Float, y: Float) {
        val best = nearestKey(x, y)
        if (best !== pressed) {
            if (best == null) removeCallbacks(deleteRepeat)
            pressed = best
            if (pressed != null) {
                SettingsStore.keyVibrate(context)
            }
            invalidate()
        }
    }

    /** 空格键上横向拖动 → 光标左右移动（Gboard 式）。返回 true = 本事件已被光标模式接管 */
    private fun handleCursorSlide(x: Float, size: Float): Boolean {
        val dx = x - trace.first()[0]
        if (!cursorSlide) {
            if (pressed?.text != " " || buf.isNotEmpty()) return false
            if (abs(dx) < 0.05f * safeRadius(size)) return false
            cursorSlide = true
            pressed = null
            removeCallbacks(spaceLongPress)
            cursorBase = keyListener?.cursorPos() ?: 0
            cursorLast = cursorBase
        }
        val step = max(6f, 0.045f * safeRadius(size))   // 每 4.5% 安全半径移动 1 字符
        val target = cursorBase + Math.round(dx / step)
        if (target != cursorLast) {
            keyListener?.onCursorSet(target)
            cursorLast = target
            SettingsStore.keyVibrate(context)
        }
        return true
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trace.clear()
                trace.add(floatArrayOf(x, y))
                tracing = false
                emojiSwiped = false
                cursorSlide = false
                // 手指是否落在分类页签条带内（表情层：拖动页签条，而非翻页）
                tabTouch = mode == MODE_EMOJI && emojiTabSpacing > 0f &&
                    abs(y - emojiTabY) < emojiTabHh * 2.6f
                tabDragging = false
                tabScrollAtDragStart = emojiTabScroll
                decoder?.clear()
                updatePressed(x, y)
                if (pressed?.action == ACTION_DELETE) postDelayed(deleteRepeat, 400)
                // 长按 ?123（字母层）：进入英文直输模式
                if (pressed?.action == ACTION_MODE && mode == MODE_LETTER && !engMode) {
                    postDelayed(engLongPress, LONG_PRESS_MS)
                }
                // 长按空格：回车
                if (pressed?.text == " ") postDelayed(spaceLongPress, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val last = trace.last()
                if (tracing) {
                    if (dist(last, floatArrayOf(x, y)) > 4f) trace.add(floatArrayOf(x, y))
                    decoder?.addPoint(x, y)              // 实时喂点（内部按最小点距抽稀）
                    pressed = nearestKey(x, y)           // 手势视觉反馈
                    invalidate()
                } else {
                    val size = min(width, height).toFloat()
                    val start = trace.first()
                    if (dist(start, floatArrayOf(x, y)) > 0.12f * safeRadius(size)) {
                        removeCallbacks(engLongPress)    // 手指移开：取消长按
                        removeCallbacks(spaceLongPress)
                    }
                    if (mode == MODE_EMOJI) {
                        // 表情面板：页签条带内拖动 = 滑动分类条；其余区域横滑 = 翻页
                        val dx = x - start[0]
                        val dy = y - start[1]
                        if (tabTouch) {
                            if (!tabDragging && abs(dx) > 0.02f * safeRadius(size)) {
                                tabDragging = true
                                pressed = null
                            }
                            if (tabDragging) {
                                emojiTabScroll = (tabScrollAtDragStart + dx)
                                    .coerceIn(-emojiTabMaxOff, emojiTabMaxOff)
                                layoutKeys(width.toFloat(), height.toFloat())
                            } else {
                                updatePressed(x, y)
                            }
                        } else if (!emojiSwiped && hypot(dx, dy) > 0.18f * safeRadius(size) &&
                            abs(dx) > abs(dy)
                        ) {
                            emojiSwiped = true
                            pressed = null
                            removeCallbacks(deleteRepeat)
                            flipEmojiPage(if (dx < 0) 1 else -1)
                        } else if (!emojiSwiped) {
                            updatePressed(x, y)
                        }
                    } else if (handleCursorSlide(x, size)) {
                        // 空格横向滑动：光标模式接管（优先于滑行手势）
                    } else if (dist(start, floatArrayOf(x, y)) > GESTURE_START * safeRadius(size)) {
                        tracing = true                   // 进入滑行模式
                        pressed = null
                        removeCallbacks(deleteRepeat)
                        decoder?.clear()
                        decoder?.addPoint(start[0], start[1])
                        decoder?.addPoint(x, y)
                        SettingsStore.keyVibrate(context)
                    } else {
                        updatePressed(x, y)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(deleteRepeat)
                removeCallbacks(engLongPress)
                removeCallbacks(spaceLongPress)
                lastTapX = x
                val longFired = engLongFired
                engLongFired = false
                if (cursorSlide) {
                    // 空格滑动已移动光标，不触发点按
                } else if (tabDragging) {
                    // 页签条滑动结束：吸附到最近页签中心
                    tabDragging = false
                    if (emojiTabSpacing > 0f) {
                        val snapped = (emojiTabScroll / emojiTabSpacing).toInt() * emojiTabSpacing
                        emojiTabScroll = snapped.coerceIn(-emojiTabMaxOff, emojiTabMaxOff)
                        layoutKeys(width.toFloat(), height.toFloat())
                        invalidate()
                    }
                } else if (emojiSwiped) {
                    // 横滑翻页已完成，不触发点按
                } else if (tracing) {
                    finishGesture()
                } else if (!longFired) {
                    pressed?.let { fireKey(it) }
                }
                pressed = null
                tracing = false
                emojiSwiped = false
                cursorSlide = false
                trace.clear()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(deleteRepeat)
                removeCallbacks(engLongPress)
                removeCallbacks(spaceLongPress)
                tabTouch = false
                tabDragging = false
                engLongFired = false
                pressed = null
                tracing = false
                emojiSwiped = false
                cursorSlide = false
                trace.clear()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    /** 表情面板横滑翻页 */
    private fun flipEmojiPage(dir: Int) {
        val list = currentEmojiList()
        if (list.isEmpty()) return
        val maxPage = (list.size + 19) / 20
        emojiPage = ((emojiPage + dir) % maxPage + maxPage) % maxPage
        SettingsStore.keyVibrate(context)
        layoutKeys(width.toFloat(), height.toFloat())
        invalidate()
    }

    // ============================== 按键动作 ==============================

    private fun fireKey(k: Key) {
        // 候选词上屏（emoji 键也用 candIndex，单独判断）
        if (k.candIndex >= 0 && !k.emoji && k.action != ACTION_EMOJI_TAB) {
            val global = candPage * CAND_PER_PAGE + k.candIndex
            val text = candidates.getOrNull(global) ?: k.text ?: return
            if (rimeComposing) {
                // rime：按全局序号选候选，commit 由引擎返回
                val snap = RimeEngine.selectCandidate(global)
                if (snap != null) {
                    snap.commit?.let { keyListener?.onText(it) }
                    applyRimeSnapshot(snap)
                    return
                }
                clearRimeLocal()             // 选择失败：清组合避免卡死
                keyListener?.onText(text)
                resetComposition()
                return
            }
            keyListener?.onText(text)
            if (buf.isNotEmpty()) store.learn(buf.toString(), text)   // 自学习
            resetComposition()
            return
        }

        when {
            k.action == ACTION_SHIFT -> {
                val now = System.currentTimeMillis()
                if (now - lastShiftTap < 350) {
                    capsLock = !capsLock                 // 双击锁定大写
                } else {
                    shift = !shift
                    capsLock = false
                }
                lastShiftTap = now
                invalidate()
            }
            k.action == ACTION_MIC -> {
                startVoiceInput()
                invalidate()
            }
            k.action == ACTION_MODE -> {
                clearRimeLocal()
                if (engMode) {
                    engMode = false                      // EN 键：切回中文
                } else {
                    mode = if (mode == MODE_SYMBOL) MODE_LETTER else MODE_SYMBOL
                    symPage = 0
                }
                relayout()
            }
            k.action == ACTION_CAND_PREV -> {
                if (candPage > 0) {
                    candPage--
                    SettingsStore.keyVibrate(context)
                    layoutKeys(width.toFloat(), height.toFloat())
                    invalidate()
                }
            }
            k.action == ACTION_CAND_NEXT -> {
                val maxPage = (candidates.size + CAND_PER_PAGE - 1) / CAND_PER_PAGE
                if (candPage < maxPage - 1) {
                    candPage++
                    SettingsStore.keyVibrate(context)
                    layoutKeys(width.toFloat(), height.toFloat())
                    invalidate()
                }
            }
            k.action == ACTION_ENG -> {
                clearRimeLocal()
                engMode = false
                relayout()
            }
            k.action == ACTION_EMOJI -> {
                clearRimeLocal()
                mode = if (mode == MODE_EMOJI) MODE_LETTER else MODE_EMOJI
                if (mode == MODE_EMOJI && emojiCat == 0 && recentEmoji.isEmpty()) {
                    emojiCat = 1   // 从未用过表情：默认进「笑脸」分类
                    emojiPage = 0
                }
                if (mode == MODE_EMOJI) {
                    emojiTabSnapPending = true   // 打开面板：吸附当前分类页签
                }
                relayout()
            }
            k.action == ACTION_SYMPAGE -> {
                symPage = 1 - symPage
                relayout()
            }
            k.action == ACTION_LETTER -> {
                mode = MODE_LETTER
                relayout()
            }
            k.action == ACTION_EMOJI_TAB -> {
                emojiCat = k.candIndex
                emojiPage = 0
                emojiTabSnapPending = true   // 布局时把选中页签吸附到条带中心
                relayout()
            }
            k.emoji && k.action < 0 -> {
                // 表情直接上屏并记入「最近」
                keyListener?.onText(k.text ?: return)
                k.text?.let { e ->
                    recentEmoji.remove(e)
                    recentEmoji.add(0, e)
                    if (recentEmoji.size > 30) recentEmoji.removeAt(recentEmoji.size - 1)
                }
                SettingsStore.keyVibrate(context)
                layoutKeys(width.toFloat(), height.toFloat())
                invalidate()
            }
            k.action == ACTION_DELETE -> handleDelete()
            k.action == ACTION_IME_PICKER || k.action == ACTION_HIDE ->
                keyListener?.onAction(k.action)
            k.action == ACTION_ENTER -> {
                // 回车：rime 组合中先上屏原始输入串，再换行
                if (rimeComposing) {
                    val snap = RimeEngine.processKey(RimeEngine.KC_RETURN)
                    if (snap != null) {
                        snap.commit?.let { keyListener?.onText(it) }
                        applyRimeSnapshot(snap)
                    } else {
                        keyListener?.onText(buf.toString())
                        clearRimeLocal()
                        resetComposition()
                    }
                } else if (buf.isNotEmpty()) {
                    keyListener?.onText(buf.toString())
                    resetComposition()
                }
                keyListener?.onAction(ACTION_ENTER)
            }
            // 九键数字键：进 T9 组合；「1」= 句读键（组合中上屏首选，空闲上屏「。」）
            k.text != null && k.text!!.length == 1 && k.text!![0] in '1'..'9' &&
                mode == MODE_LETTER && !engMode &&
                SettingsStore.keyLayout(context) == SettingsStore.LAYOUT_9 -> {
                val d = k.text!![0]
                when {
                    d == '1' && buf.isNotEmpty() -> {
                        keyListener?.onText(
                            if (candidates.isEmpty()) buf.toString() else candidates[0])
                        resetComposition()
                    }
                    d == '1' -> keyListener?.onText("。")
                    store.hasDictionary -> {
                        buf.append(d)
                        updateCandidates()
                    }
                    else -> keyListener?.onText(d.toString())   // 无词库：数字直通
                }
            }
            // 双键：按键左右半区选字母（左半 = 前字母，右半 = 后字母）
            k.chars != null && k.text == null -> {
                val pair = k.chars!!
                var ch = if (lastTapX < k.x) pair[0] else pair[1]
                if (mode == MODE_LETTER && shift && !capsLock && ch.isLowerCase()) {
                    ch = ch.uppercaseChar()
                }
                if (rimeMode()) {
                    feedRimeText(ch.toString())
                } else if (mode == MODE_LETTER && store.hasDictionary && !engMode) {
                    buf.append(ch.toString())
                    updateCandidates()
                } else {
                    keyListener?.onText(ch.toString())
                    if (shift && !capsLock) shift = false
                }
                invalidate()
            }
            k.text != null -> {
                val t0 = k.text!!
                if (t0 == " ") {
                    handleSpace()
                } else if (rimeMode() && t0.length == 1 && t0[0].isLetter()) {
                    feedRimeText(t0)                 // rime 组合（26/双键中文）
                } else if (mode == MODE_LETTER && store.hasDictionary && !engMode) {
                    buf.append(t0)                       // 有词库：字母进入组合串
                    updateCandidates()
                } else {
                    var t = t0
                    if (mode == MODE_LETTER && shift && t.length == 1 && t[0].isLowerCase()) {
                        t = t.uppercase()
                    }
                    keyListener?.onText(t)
                    if (shift && !capsLock) {
                        shift = false
                        invalidate()
                    }
                }
            }
        }
    }

    /** 长按空格：回车（工具条 ↵ 让位给 🎤 后的回车入口） */
    private val spaceLongPress = Runnable {
        if (pressed?.text == " ") {
            pressed = null
            SettingsStore.keyVibrate(context)
            keyListener?.onAction(ACTION_ENTER)
        }
    }

    /** 麦克风：语音识别（Gboard Wear 同款工具条位），识别结果整段上屏 */
    private fun startVoiceInput() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Toast.makeText(context, "请打开应用设置授权麦克风", Toast.LENGTH_SHORT).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, "此手表不支持语音识别", Toast.LENGTH_SHORT).show()
            return
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) keyListener?.onText(t)
                sr.destroy()
            }
            override fun onError(error: Int) { sr.destroy() }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                     RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        sr.startListening(intent)
    }

    /** 空格：rime 组合中确认首选候选；否则有组合串上屏首选（或原始串），空闲上屏空格 */
    private fun handleSpace() {
        if (rimeComposing) {
            val snap = RimeEngine.processKey(RimeEngine.KC_SPACE)
            if (snap != null) {
                snap.commit?.let { keyListener?.onText(it) }
                applyRimeSnapshot(snap)
                if (!rimeComposing && snap.commit == null) keyListener?.onText(" ")
                return
            }
            // rime 未接管：兜底上屏首选
            val t = if (candidates.isEmpty()) buf.toString() else candidates[0]
            keyListener?.onText(t)
            resetComposition()
            keyListener?.onText(" ")
            return
        }
        if (buf.isNotEmpty()) {
            val t = if (candidates.isEmpty()) buf.toString() else candidates[0]
            keyListener?.onText(t)
            if (candidates.isNotEmpty()) store.learn(buf.toString(), t)   // 自学习
            resetComposition()
        }
        keyListener?.onText(" ")
    }

    private fun handleDelete() {
        if (rimeComposing) {
            // rime：BackSpace = 回删一个输入字符（仅组合中接管；提交后交还系统删除）
            val snap = RimeEngine.processKey(RimeEngine.KC_BACKSPACE)
            if (snap != null) {
                applyRimeSnapshot(snap)
                return
            }
            rimeComposing = false
        }
        if (buf.isNotEmpty()) {
            buf.deleteCharAt(buf.length - 1)
            updateCandidates()
        } else {
            keyListener?.onAction(ACTION_DELETE)
        }
    }

    private fun resetComposition() {
        clearRimeLocal()
        buf.setLength(0)
        candidates.clear()
        candPage = 0
        layoutKeys(width.toFloat(), height.toFloat())
        invalidate()
    }
}
