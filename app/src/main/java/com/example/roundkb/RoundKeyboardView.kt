package com.example.roundkb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
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

        // ---- 几何参数（按表盘大小微调） ----
        private const val MARGIN_FRACTION = 0.05f    // 外边距占直径比例
        private const val MIN_MARGIN_PX = 10f        // 边距下限
        private const val SAFE_FACTOR = 0.965f       // 安全圆系数（越界自动拉回）
        private const val HIT_SLOP_MUL = 1.15f       // 触点容错
        private const val HIT_SLOP_ADD = 8f
        private const val GESTURE_START = 0.30f      // 起点位移 > 0.30R 进入滑行
        private const val GESTURE_MIN_PATH = 0.85f   // 轨迹最短长度（相对安全半径）
        private const val GESTURE_SAMPLES = 24       // 轨迹/理想路径重采样数

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
        private val SYM0 = "1234567890".map { it.toString() }
        private val SYM1 = listOf("@", "#", "￥", "%", "&", "*", "-", "+", "/")
        private val SYM2 = listOf(".", ",", "?", "!", "'", "\"", ":")

        /** 行宽占该高度处圆弧弦长的比例（直排下控制左右边距） */
        private const val ROW_SPAN = 0.88f
    }

    private class Key {
        var label: String = ""
        var text: String? = null
        var action: Int = -1
        var candIndex: Int = -1
        var x = 0f; var y = 0f
        var hw = 0f; var hh = 0f   // 圆角矩形半宽/半高
        var control = false
    }

    private val keys = mutableListOf<Key>()
    private val letterPos = HashMap<Char, FloatArray>()   // 滑行匹配用

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()
    private val trailPath = Path()

    var keyListener: KeyListener? = null

    private var pressed: Key? = null
    private var symbols = false
    private var shift = false
    private var capsLock = false
    private var lastShiftTap = 0L

    private val buf = StringBuilder()
    private val candidates = mutableListOf<String>()
    private val store: WordStore = WordStore.get(context)

    // 滑行手势状态
    private val trace = mutableListOf<FloatArray>()
    private var tracing = false

    private val deleteRepeat = object : Runnable {
        override fun run() {
            handleDelete()
            postDelayed(this, 60)
        }
    }

    init {
        textPaint.color = COLOR_TEXT
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
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

    private fun safeRadius(size: Float): Float = size / 2f - max(MIN_MARGIN_PX, size * MARGIN_FRACTION)

    private fun layoutKeys(w: Float, h: Float) {
        keys.clear()
        letterPos.clear()
        if (w <= 0 || h <= 0) return

        val size = min(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val R = safeRadius(size)

        val candMode = buf.isNotEmpty() && candidates.isNotEmpty()

        if (!symbols) {
            if (candMode) {
                addCandidateRow(-0.66f, cx, cy, R)
                addRow(ROW0, -0.42f, cx, cy, R)
                addRow(ROW1, -0.10f, cx, cy, R)
                addBottomRow(0.22f, cx, cy, R)
            } else {
                addRow(ROW0, -0.44f, cx, cy, R)
                addRow(ROW1, -0.12f, cx, cy, R)
                addBottomRow(0.20f, cx, cy, R)
            }
            addControls(cx, cy, R)
        } else {
            addRow(SYM0, -0.44f, cx, cy, R)
            addRow(SYM1, -0.12f, cx, cy, R)
            val bottom = mutableListOf("ABC")
            bottom.addAll(SYM2)
            bottom.add("DEL")
            addRow(bottom, 0.20f, cx, cy, R)
            addControls(cx, cy, R)
        }

        clampToSafeCircle(cx, cy, R)
    }

    /** 底排：⇧ + Z..M + ⌫（shift/退格与字母同行，Gboard 式） */
    private fun addBottomRow(y0f: Float, cx: Float, cy: Float, R: Float) {
        val bottom = mutableListOf("SHIFT")
        bottom.addAll(ROW2)
        bottom.add("DEL")
        addRow(bottom, y0f, cx, cy, R)
    }

    /** 底部控制区：123 / 宽胶囊空格 / ⏎ */
    private fun addControls(cx: Float, cy: Float, R: Float) {
        addKey("123", null, ACTION_MODE, cx - 0.52f * R, cy + 0.585f * R, 0.130f * R, 0.115f * R, true)
        addKey("␣", " ", -1, cx, cy + 0.630f * R, 0.360f * R, 0.125f * R, true)
        addKey("⏎", null, ACTION_ENTER, cx + 0.52f * R, cy + 0.585f * R, 0.130f * R, 0.115f * R, true)
    }

    /**
     * 水平直排一行按键（Gboard 式，不做弧形弯曲）。
     * 行宽 = 该行高度处圆弧弦长 × ROW_SPAN，两端自然收进圆内。
     * @param y0f 行中心纵向偏移（相对 R，负 = 上方）
     */
    private fun addRow(items: List<String>, y0f: Float, cx: Float, cy: Float, R: Float) {
        val y0 = cy + y0f * R
        val dy = y0f * R
        val chordHalf = sqrt(max(0f, R * R - dy * dy))
        val halfSpan = chordHalf * ROW_SPAN
        if (items.isEmpty() || halfSpan <= 0f) return

        val step = 2f * halfSpan / items.size
        val keyR = min(0.092f * R, step * 0.52f)

        for ((i, s) in items.withIndex()) {
            val x = cx - halfSpan + step * (i + 0.5f)

            when (s) {
                "SHIFT" -> addKey("⇧", null, ACTION_SHIFT, x, y0, keyR, keyR, true)
                "DEL" -> addKey("⌫", null, ACTION_DELETE, x, y0, keyR, keyR, true)
                "ABC", "123" -> addKey(s, null, ACTION_MODE, x, y0, keyR, keyR, true)
                else -> {
                    addKey(s, s.lowercase(), -1, x, y0, keyR, keyR, false)
                    letterPos[s.lowercase()[0]] = floatArrayOf(x, y0)
                }
            }
        }
    }

    /** 候选词沿顶部弧排布（1~3 个胶囊芯片） */
    private fun addCandidateRow(y0f: Float, cx: Float, cy: Float, R: Float) {
        if (candidates.isEmpty()) return
        val y = cy + y0f * R
        val hh = 0.085f * R
        val spacing = 0.335f * R
        candidates.forEachIndexed { i, text ->
            val k = Key()
            k.label = text
            k.text = text
            k.candIndex = i
            k.x = cx + (i - (candidates.size - 1) / 2f) * spacing
            k.y = y
            k.hw = 0.155f * R
            k.hh = hh
            k.control = true
            keys.add(k)
        }
    }

    private fun addKey(label: String, text: String?, action: Int,
                       x: Float, y: Float, hw: Float, hh: Float, control: Boolean) {
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
            candidates.addAll(correctAndSuggest(p, 3))
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

    /** 折线等弧长重采样为 n 个点 */
    private fun resample(pts: List<FloatArray>, n: Int): Array<FloatArray>? {
        if (pts.size < 2) return null
        val total = pathLength(pts)
        if (total <= 0f) return null
        val out = Array(n) { FloatArray(2) }
        val stepLen = total / (n - 1)
        var acc = 0f
        var seg = 0
        out[0][0] = pts[0][0]
        out[0][1] = pts[0][1]
        for (i in 1 until n) {
            val target = stepLen * i
            while (seg < pts.size - 2 && acc + dist(pts[seg], pts[seg + 1]) < target) {
                acc += dist(pts[seg], pts[seg + 1])
                seg++
            }
            val segLen = dist(pts[seg], pts[seg + 1])
            val f = if (segLen <= 0f) 0f else (target - acc) / segLen
            val a = pts[seg]
            val b = pts[seg + 1]
            out[i][0] = a[0] + (b[0] - a[0]) * f
            out[i][1] = a[1] + (b[1] - a[1]) * f
        }
        return out
    }

    /** 轨迹 → 字母序列（相邻去重，仅字母键） */
    private fun traceToSequence(): List<String> {
        val seq = mutableListOf<String>()
        var last: String? = null
        for (pt in trace) {
            var best: Key? = null
            var bestD = Float.MAX_VALUE
            for (k in keys) {
                val t = k.text
                if (t == null || k.control || k.candIndex >= 0 ||
                    t.length != 1 || !t[0].isLetter()) continue
                val dx = max(abs(pt[0] - k.x) - k.hw, 0f)
                val dy = max(abs(pt[1] - k.y) - k.hh, 0f)
                val d = hypot(dx, dy)
                if (d < bestD) { bestD = d; best = k }
            }
            val b = best
            if (b != null && bestD < b.hh * 2.2f) {
                val ch = b.text!!
                if (ch != last) { seq.add(ch); last = ch }
            }
        }
        return seq
    }

    /** 手势匹配：首尾字母过滤 + 理想路径与轨迹的贴合度打分 */
    private fun gestureCandidates(seq: List<String>): List<String> {
        val out = mutableListOf<String>()
        if (seq.size < 2 || !store.hasDictionary) return out

        val ts = resample(trace, GESTURE_SAMPLES) ?: return out
        val traceLen = pathLength(trace)
        val R = safeRadius(min(width, height).toFloat())
        val seqStr = seq.joinToString("")
        val words = store.gestureFilter(seq.first(), seq.last(), 6000)

        // 综合打分：双向 Chamfer（路径贴合 + 蜿蜒惩罚）+ 字母必经性 + 长度差 - 词频加成
        data class Scored(val value: String, val cost: Float, val freq: Int)
        val scored = mutableListOf<Scored>()
        for (gw in words) {
            // 词字母序列必须是轨迹字母序列的子序列（允许路过别的键，但顺序不能乱）
            if (!isSubsequence(gw.key, seqStr)) continue

            // 理想路径：词的键心折线（相邻去重）
            val ideal = mutableListOf<FloatArray>()
            var prev: FloatArray? = null
            var ok = true
            for (ch in gw.key) {
                val c = letterPos[ch]
                if (c == null) { ok = false; break }
                if (prev == null || dist(prev, c) > 0.01f) ideal.add(c)
                prev = c
            }
            if (!ok || ideal.size < 2) continue

            val isamp = resample(ideal, GESTURE_SAMPLES) ?: continue

            // c1：理想路径每点到轨迹的最小距离（覆盖：词的路径要贴着轨迹走）
            // c2：轨迹每点到理想路径的最小距离（蜿蜒：轨迹别乱跑）
            var c1 = 0f
            var c2 = 0f
            for (i in 0 until GESTURE_SAMPLES) {
                var b1 = Float.MAX_VALUE
                var b2 = Float.MAX_VALUE
                for (j in 0 until GESTURE_SAMPLES) {
                    val d1 = dist(isamp[i], ts[j])
                    if (d1 < b1) b1 = d1
                    val d2 = dist(ts[i], isamp[j])
                    if (d2 < b2) b2 = d2
                }
                c1 += b1
                c2 += b2
            }

            // 必经性：词的每个字母键心都应被轨迹经过（取最小距离均值）
            var visit = 0f
            var nLetters = 0
            var prevCh = ' '
            for (ch in gw.key) {
                if (ch == prevCh) continue
                prevCh = ch
                val c = letterPos[ch] ?: continue
                var bd = Float.MAX_VALUE
                for (t in ts) {
                    val d = dist(c, t)
                    if (d < bd) bd = d
                }
                visit += bd
                nLetters++
            }
            if (nLetters > 0) visit /= nLetters

            var idealLen = 0f
            for (i in 1 until ideal.size) idealLen += dist(ideal[i - 1], ideal[i])

            val freqBonus = min(0.10f, 0.03f * kotlin.math.ln(1f + gw.freq))
            val cost = 0.5f * (c1 + c2) / (GESTURE_SAMPLES * R) +
                    0.35f * visit / R +
                    0.15f * abs(idealLen - traceLen) / R -
                    freqBonus
            scored.add(Scored(gw.value, cost, gw.freq))
        }
        scored.sortWith(compareBy<Scored> { it.cost }.thenByDescending { it.freq })
        for (s in scored) {
            if (out.size >= 3) break
            if (s.cost < 0.50f) out.add(s.value)
        }
        return out
    }

    /** s 是否为 of 的子序列（手势轨迹允许带多余字母，但词字母顺序必须一致） */
    private fun isSubsequence(s: String, of: String): Boolean {
        var i = 0
        for (c in of) {
            if (i < s.length && s[i] == c) i++
        }
        return i == s.length
    }

    private fun finishGesture() {
        val size = min(width, height).toFloat()
        val minLen = GESTURE_MIN_PATH * safeRadius(size)
        if (pathLength(trace) < minLen) return

        val seq = traceToSequence()
        if (seq.size < 2) return

        buf.setLength(0)
        buf.append(seq.joinToString(""))
        candidates.clear()
        val cands = gestureCandidates(seq)
        if (cands.isNotEmpty()) {
            candidates.addAll(cands)                                   // 手势最优词
        } else {
            candidates.addAll(correctAndSuggest(buf.toString(), 3))    // 退化为组合纠错
        }
        layoutKeys(width.toFloat(), height.toFloat())
        invalidate()
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
                k.candIndex == 0 -> { fill = COLOR_ACCENT; txt = COLOR_TEXT_DARK }  // 首选候选
                k.candIndex > 0 -> fill = COLOR_KEY_CTL
                k.action == ACTION_ENTER -> { fill = COLOR_ACCENT; txt = COLOR_TEXT_DARK }
                k.action == ACTION_SHIFT && (shift || capsLock) ->
                    { fill = COLOR_ACCENT; txt = COLOR_TEXT_DARK }
                k.action == ACTION_MODE && symbols ->
                    { fill = COLOR_ACCENT; txt = COLOR_TEXT_DARK }
                k.text == " " -> fill = COLOR_KEY_SPACE
                k.control -> fill = COLOR_KEY_CTL
                else -> fill = COLOR_KEY
            }
            keyPaint.color = fill
            val corner = if (k.text == " ") k.hh else k.hh * 0.62f
            rectF.set(k.x - k.hw, k.y - k.hh, k.x + k.hw, k.y + k.hh)
            canvas.drawRoundRect(rectF, corner, corner, keyPaint)

            var label = k.label
            if (k.action == ACTION_SHIFT && capsLock) label = "⇪"
            if (k.text == " " && buf.isNotEmpty()) label = buf.toString()   // 组合串显示在空格键
            val t = k.text
            if (t != null && !k.control && k.candIndex < 0 && shift && !symbols && !capsLock &&
                t.length == 1 && t[0].isLowerCase()
            ) {
                label = label.uppercase()
            }

            var size = k.hh * (if (k.control) 1.5f else 1.7f)
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
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
                updatePressed(x, y)
                if (pressed?.action == ACTION_DELETE) postDelayed(deleteRepeat, 400)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val last = trace.last()
                if (tracing) {
                    if (dist(last, floatArrayOf(x, y)) > 4f) trace.add(floatArrayOf(x, y))
                    pressed = nearestKey(x, y)          // 手势视觉反馈
                    invalidate()
                } else {
                    val size = min(width, height).toFloat()
                    val start = trace.first()
                    if (dist(start, floatArrayOf(x, y)) > GESTURE_START * safeRadius(size)) {
                        tracing = true                   // 进入滑行模式
                        pressed = null
                        removeCallbacks(deleteRepeat)
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    } else {
                        updatePressed(x, y)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(deleteRepeat)
                if (tracing) {
                    finishGesture()
                } else {
                    pressed?.let { fireKey(it) }
                }
                pressed = null
                tracing = false
                trace.clear()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(deleteRepeat)
                pressed = null
                tracing = false
                trace.clear()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(ev)
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
                symbols = !symbols
                relayout()
            }
            k.action == ACTION_DELETE -> handleDelete()
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
                } else if (!symbols && store.hasDictionary) {
                    buf.append(t0)                       // 有词库：字母进入组合串
                    updateCandidates()
                } else {
                    var t = t0
                    if (!symbols && shift && t.length == 1 && t[0].isLowerCase()) {
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
