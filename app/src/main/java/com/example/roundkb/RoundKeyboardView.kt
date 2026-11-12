package com.example.roundkb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
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
    private var emojiCat = 0                // 表情分类（0=最近）
    private var emojiPage = 0               // 表情分类内页码
    private val recentEmoji = mutableListOf<String>()   // 最近使用的表情（内存）
    private var emojiSwiped = false         // 表情面板横滑翻页
    private var shift = false
    private var capsLock = false
    private var lastShiftTap = 0L

    private val buf = StringBuilder()
    private val candidates = mutableListOf<String>()
    private val store: WordStore = WordStore.get(context)

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
            MODE_LETTER -> {
                addRow(ROW0, -0.30f, cx, cy, R, unit, bare = true)
                addRow(ROW1, -0.02f, cx, cy, R, unit, bare = true)
                addRow(ROW2, 0.26f, cx, cy, R, unit, bare = true)
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
            val t = k.text ?: continue
            if (k.control || k.candIndex >= 0 || t.length != 1) continue
            val ch = t[0].lowercaseChar()
            if (!ch.isLetter() || !seen.add(ch)) continue
            letterKeys.add(GlideKey(ch, k.x, k.y, k.hw * 2f, k.hh * 2f))
        }
        val d = GlideDecoder(store.endpointIndex(), letterKeys, store.maxFrequency)
        d.setMinPointDistance((letterKeys.firstOrNull()?.w?.div(4f)) ?: 12f)
        decoder = d
    }

    /** 顶部工具条：‹ 切换输入法 / 😀 表情 / ?123 符号 / ↵ 回车 / ✕ 收起（Gboard Wear 式） */
    private fun addToolbar(y0f: Float, cx: Float, cy: Float, R: Float, s: Float) {
        val y = cy + y0f * R
        val hw = 0.098f * R * s
        val hh = 0.085f * R * s
        addKey("‹", null, ACTION_IME_PICKER, cx - 0.46f * R, y, hw, hh, true).apply {
            iconRes = AppR.drawable.ic_chevron_left
        }
        addKey("😀", null, ACTION_EMOJI, cx - 0.23f * R, y, hw, hh, true).apply {
            emoji = true
        }
        addKey(if (mode == MODE_SYMBOL) "ABC" else "?123", null, ACTION_MODE, cx, y, hw, hh, true)
        addKey("↵", null, ACTION_ENTER, cx + 0.23f * R, y, hw, hh, true).apply {
            iconRes = AppR.drawable.ic_return
        }
        addKey("✕", null, ACTION_HIDE, cx + 0.46f * R, y, hw, hh, true).apply {
            iconRes = AppR.drawable.ic_close
        }
    }

    /** 表情层：分类页签 + 5×4 网格（横滑翻页，页码圆点在 onDraw 画） */
    private fun addEmojiGrid(cx: Float, cy: Float, R: Float, s: Float) {
        // 分类页签（页签之间留出明显间隙，避免挤成一条线）
        val tabY = cy - 0.56f * R
        val tabN = EmojiData.names.size
        val tabSpacing = 0.105f * R
        for (i in 0 until tabN) {
            val x = cx + (i - (tabN - 1) / 2f) * tabSpacing
            addKey(EmojiData.tabIcons[i], null, ACTION_EMOJI_TAB, x, tabY,
                0.048f * R, 0.045f * R, true).apply {
                emoji = true
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
            MODE_LETTER -> addKey("⇧", null, ACTION_SHIFT, cx - 0.40f * R, y, hw, hh, true).apply {
                noFill = true
                iconRes = AppR.drawable.ic_capslock
            }
            MODE_SYMBOL -> addKey(if (symPage == 0) "=\\<" else "?123", null, ACTION_SYMPAGE,
                cx - 0.40f * R, y, hw, hh, true)
            else -> addKey("ABC", null, ACTION_LETTER, cx - 0.40f * R, y, hw, hh, true)
        }
        if (mode != MODE_EMOJI) {
            addKey("␣", " ", -1, cx, y, 0.26f * R * s, 0.105f * R * s, true).apply {
                iconRes = AppR.drawable.ic_space
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

    /** 候选词沿顶部弧排布（1~3 个胶囊芯片） */
    private fun addCandidateRow(y0f: Float, cx: Float, cy: Float, R: Float) {
        if (candidates.isEmpty()) return
        val y = cy + y0f * R
        val s = SettingsStore.keyScale(context)   // 候选胶囊随全局按键缩放
        val hh = 0.085f * R * s
        val spacing = 0.335f * R * s
        candidates.forEachIndexed { i, text ->
            val k = Key()
            k.label = text
            k.text = text
            k.candIndex = i
            k.x = cx + (i - (candidates.size - 1) / 2f) * spacing
            k.y = y
            k.hw = 0.155f * R * s
            k.hh = hh
            k.control = true
            keys.add(k)
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
        val p = buf.toString()
        if (p.isNotEmpty() && store.hasDictionary) {
            // 整句拼音优先：连续按键串 → 自动分词组句；不足再补词级前缀候选
            if (p.length >= 4) candidates.addAll(store.sentence(p, 3))
            if (candidates.size < 3) {
                for (w in correctAndSuggest(p, 3 - candidates.size)) {
                    if (w !in candidates) candidates.add(w)
                    if (candidates.size >= 3) break
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
            if (!bare) {
                keyPaint.color = fill
                val corner = if (k.text == " ") k.hh else k.hh * 0.62f
                rectF.set(k.x - k.hw, k.y - k.hh, k.x + k.hw, k.y + k.hh)
                canvas.drawRoundRect(rectF, corner, corner, keyPaint)
            }

            // 图标键（Material Icons 矢量）：空格键有组合串时仍显示文字
            val showIcon = k.iconRes != 0 && !(k.text == " " && buf.isNotEmpty())
            if (showIcon) {
                val dr = context.getDrawable(k.iconRes)
                if (dr != null) {
                    dr.setTint(txt)
                    // 图标边长 = 键短边 × 基准 0.95 × 全局缩放（设置页可调 小/标准/大）
                    val s = min(k.hw, k.hh) * 0.95f * SettingsStore.iconScale(context)
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
            val ty = k.y - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, k.x, ty, textPaint)
        }

        // 表情层附加绘制：最近分类为空时的提示 + 页码圆点
        if (mode == MODE_EMOJI) {
            val size = min(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            val R = safeRadius(size)
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
        return if (b != null && bestD <= b.hh * HIT_SLOP_MUL + HIT_SLOP_ADD) b else null
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

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trace.clear()
                trace.add(floatArrayOf(x, y))
                tracing = false
                emojiSwiped = false
                decoder?.clear()
                updatePressed(x, y)
                if (pressed?.action == ACTION_DELETE) postDelayed(deleteRepeat, 400)
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
                    if (mode == MODE_EMOJI) {
                        // 表情面板：横滑翻页（不参与滑行打字）
                        val dx = x - start[0]
                        val dy = y - start[1]
                        if (!emojiSwiped && hypot(dx, dy) > 0.18f * safeRadius(size) &&
                            abs(dx) > abs(dy)
                        ) {
                            emojiSwiped = true
                            pressed = null
                            removeCallbacks(deleteRepeat)
                            flipEmojiPage(if (dx < 0) 1 else -1)
                        } else if (!emojiSwiped) {
                            updatePressed(x, y)
                        }
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
                if (emojiSwiped) {
                    // 横滑翻页已完成，不触发点按
                } else if (tracing) {
                    finishGesture()
                } else {
                    pressed?.let { fireKey(it) }
                }
                pressed = null
                tracing = false
                emojiSwiped = false
                trace.clear()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(deleteRepeat)
                pressed = null
                tracing = false
                emojiSwiped = false
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
        // 候选词上屏
        if (k.candIndex >= 0) {
            k.text?.let { keyListener?.onText(it) }
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
            k.action == ACTION_MODE -> {
                mode = if (mode == MODE_SYMBOL) MODE_LETTER else MODE_SYMBOL
                symPage = 0
                relayout()
            }
            k.action == ACTION_EMOJI -> {
                mode = if (mode == MODE_EMOJI) MODE_LETTER else MODE_EMOJI
                if (mode == MODE_EMOJI && emojiCat == 0 && recentEmoji.isEmpty()) {
                    emojiCat = 1   // 从未用过表情：默认进「笑脸」分类
                    emojiPage = 0
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
                // 回车：先上屏未提交的组合串（若有），再换行
                if (buf.isNotEmpty()) {
                    keyListener?.onText(buf.toString())
                    resetComposition()
                }
                keyListener?.onAction(ACTION_ENTER)
            }
            k.text != null -> {
                val t0 = k.text!!
                if (t0 == " ") {
                    handleSpace()
                } else if (mode == MODE_LETTER && store.hasDictionary) {
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

    /** 空格：有组合串时上屏首选候选（或原始串），否则上屏空格 */
    private fun handleSpace() {
        if (buf.isNotEmpty()) {
            val t = if (candidates.isEmpty()) buf.toString() else candidates[0]
            keyListener?.onText(t)
            resetComposition()
        }
        keyListener?.onText(" ")
    }

    private fun handleDelete() {
        if (buf.isNotEmpty()) {
            buf.deleteCharAt(buf.length - 1)
            updateCandidates()
        } else {
            keyListener?.onAction(ACTION_DELETE)
        }
    }

    private fun resetComposition() {
        buf.setLength(0)
        candidates.clear()
        layoutKeys(width.toFloat(), height.toFloat())
        invalidate()
    }
}
