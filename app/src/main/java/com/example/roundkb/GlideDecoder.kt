package com.example.roundkb

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** 键位：圆角矩形中心与尺寸（w/h 为整键宽高） */
class GlideKey(val ch: Char, val cx: Float, val cy: Float, val w: Float, val h: Float)

/** 词条：key = 按键序列（小写字母），value = 上屏文本，freq = 词频 */
class GlideWord(val key: String, val value: String, val freq: Int)

/**
 * 滑行输入解码器。
 *
 * 算法移植自 **FlorisBoard** 的 `StatisticalGlideTypingClassifier`
 * （Apache-2.0，https://github.com/florisboard/florisboard，
 *  `app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/`），
 * 其源头是 Étienne Desticourt 为 AnySoftKeyboard 实现的 SHARK² 变体
 * （https://github.com/AnySoftKeyboard/AnySoftKeyboard/pull/1870）。
 *
 * 与原实现一致的四级流水线：
 *  1. **采样**：按最小点距抽稀，同一位置不重复入点；
 *  2. **端点剪枝**：取手势起/终点各最近 2 个键，(首键,尾键) 命中词树；
 *  3. **长度剪枝**：理想路径长度与手势长度差 > 8.42 × 键半径 的词直接丢弃；
 *  4. **统计打分**：形状距离（按包围盒归一化 → 与键盘尺度无关）与位置距离（L1）
 *     各自套高斯概率，再乘词频概率，`confidence = 1/(Ps·Pl·Pf)` 越小越优。
 *
 * 使重复字母可区分（pool / poll）：同一键连续出现时，理想路径在该键上绕一个小环，
 * 生成「平直」与「带环」两条候选路径一并参与打分。
 *
 * 相对原实现的改动：
 *  - 词表来自应用内导入的自定义词库（key=按键序列、value=上屏文本、freq=词频），
 *    端点索引由 [WordStore] 预建，布局变化时无需重建全量索引；
 *  - 键为圆角矩形，小环半径取半宽/半高；
 *  - 词频概率用对数归一化（原实现依赖 NLP 包的 0..1 词频）。
 */
class GlideDecoder(
    private val wordsByEndpoint: Map<Long, List<GlideWord>>,
    keys: List<GlideKey>,
    private val maxFreq: Int,
) {

    companion object {
        /** 重采样点数：原实现常量，直接影响 SHAPE_STD 的标定 */
        const val SAMPLING_POINTS = 200

        /**
         * 长度剪枝容差（× 键半径）。
         *
         * 原实现为 8.42（按手机键宽标定）。圆表盘键宽只有 ~40px，且拼音长词
         * （zhongguo / nihaoma）路径跨度大、手指绕路多，8.42 会误杀，故放宽到 14。
         * 长度剪枝只做粗筛，真正的判别交给形状/位置高斯打分。
         */
        private const val PRUNING_LENGTH_THRESHOLD = 14.0f

        /** 归一化形状距离的标准差（原实现标定值，勿随意改） */
        private const val SHAPE_STD = 22.08f

        /** 位置距离标准差（× 键半径） */
        private const val LOCATION_STD = 0.5109f

        private const val MAX_POINTS = 600

        /** 端点键对 → 索引（首字符 << 16 | 尾字符） */
        fun pairKey(first: Char, last: Char): Long =
            (first.code.toLong() shl 16) or last.code.toLong()
    }

    /** 手势点序列（定长数组，避免装箱） */
    class Gesture {
        private val xs = FloatArray(MAX_POINTS)
        private val ys = FloatArray(MAX_POINTS)
        var size = 0
            private set

        val isEmpty: Boolean get() = size == 0

        fun addPoint(x: Float, y: Float) {
            if (size >= MAX_POINTS) return
            xs[size] = x
            ys[size] = y
            size++
        }

        fun clear() {
            size = 0
        }

        fun firstX(): Float = if (size > 0) xs[0] else 0f
        fun firstY(): Float = if (size > 0) ys[0] else 0f
        fun lastX(): Float = if (size > 0) xs[size - 1] else 0f
        fun lastY(): Float = if (size > 0) ys[size - 1] else 0f
        fun getX(i: Int): Float = if (i in 0 until size) xs[i] else 0f
        fun getY(i: Int): Float = if (i in 0 until size) ys[i] else 0f

        fun length(): Float {
            var len = 0f
            for (i in 1 until size) len += distance(xs[i - 1], ys[i - 1], xs[i], ys[i])
            return len
        }

        /** 等弧长重采样（带累积误差补偿，原实现同款） */
        fun resample(numPoints: Int): Gesture {
            val out = Gesture()
            if (size == 0) return out
            if (size == 1) {
                repeat(numPoints) { out.addPoint(xs[0], ys[0]) }
                return out
            }
            val inter = length() / numPoints
            if (inter <= 0.0001f) {
                repeat(numPoints) { out.addPoint(xs[0], ys[0]) }
                return out
            }
            out.addPoint(xs[0], ys[0])
            var lastX = xs[0]
            var lastY = ys[0]
            var cumErr = 0f
            for (i in 0 until size - 1) {
                var dx = xs[i + 1] - xs[i]
                var dy = ys[i + 1] - ys[i]
                val norm = sqrt(dx * dx + dy * dy)
                if (norm <= 0f) continue
                dx /= norm
                dy /= norm
                var newPoints = norm / inter
                cumErr += newPoints - newPoints.toInt()
                if (cumErr > 1f) {
                    newPoints = (newPoints.toInt() + cumErr.toInt()).toFloat()
                    cumErr %= 1f
                }
                repeat(newPoints.toInt()) {
                    lastX += dx * inter
                    lastY += dy * inter
                    out.addPoint(lastX, lastY)
                }
            }
            return out
        }

        /** 按包围盒长边归一化并移到质心 —— 形状距离与键盘/表盘大小无关 */
        fun normalizeByBoxSide(): Gesture {
            val out = Gesture()
            if (size == 0) return out
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            for (i in 0 until size) {
                if (xs[i] > maxX) maxX = xs[i]
                if (xs[i] < minX) minX = xs[i]
                if (ys[i] > maxY) maxY = ys[i]
                if (ys[i] < minY) minY = ys[i]
            }
            val width = maxX - minX
            val height = maxY - minY
            val longest = max(max(width, height), 0.00001f)
            val centroidX = (width / 2f + minX) / longest
            val centroidY = (height / 2f + minY) / longest
            for (i in 0 until size) {
                out.addPoint(xs[i] / longest - centroidX, ys[i] / longest - centroidY)
            }
            return out
        }
    }

    private val keysByChar = HashMap<Char, GlideKey>(32)
    private val letterKeys: List<GlideKey>
    private val radius: Float
    private val idealLengthCache = HashMap<String, Float>(256)

    private val gesture = Gesture()
    private var minDistSq = 1f

    init {
        for (k in keys) {
            val c = k.ch.lowercaseChar()
            if (c.isLetter() && !keysByChar.containsKey(c)) keysByChar[c] = k
        }
        letterKeys = keysByChar.values.toList()
        var sum = 0f
        for (k in letterKeys) sum += min(k.w, k.h)
        radius = if (letterKeys.isEmpty()) 1f else max(1f, sum / letterKeys.size)
    }

    /** 最小点距（通常取键宽的 1/4，原实现同款） */
    fun setMinPointDistance(px: Float) {
        val d = max(1f, px)
        minDistSq = d * d
    }

    fun clear() = gesture.clear()

    fun pathLength(): Float = gesture.length()

    fun isEmpty(): Boolean = gesture.isEmpty

    fun addPoint(x: Float, y: Float) {
        if (gesture.size > 0) {
            val dx = gesture.lastX() - x
            val dy = gesture.lastY() - y
            if (dx * dx + dy * dy > minDistSq) gesture.addPoint(x, y)
        } else {
            gesture.addPoint(x, y)
        }
    }

    /**
     * 取最优 [maxCount] 个候选（已按置信度排序）。
     * @return 上屏文本列表；无词库/无匹配时为空
     */
    fun suggest(maxCount: Int): List<String> {
        if (gesture.size < 2 || wordsByEndpoint.isEmpty()) return emptyList()

        val byEndpoint = pruneByExtremities()
        if (byEndpoint.isEmpty()) return emptyList()
        // 长度剪枝兜底：剪没了就退回端点结果，宁可多算几个也不静默丢词
        var byLength = pruneByLength(byEndpoint)
        if (byLength.isEmpty()) byLength = byEndpoint

        val userResampled = gesture.resample(SAMPLING_POINTS)
        val userNormalized = userResampled.normalizeByBoxSide()

        val best = ArrayList<Pair<String, Float>>(maxCount + 1)
        val std = LOCATION_STD * radius

        for (w in byLength) {
            for (ideal in idealGestures(w.key)) {
                val wordResampled = ideal.resample(SAMPLING_POINTS)
                val wordNormalized = wordResampled.normalizeByBoxSide()
                val shapeDistance = shapeDistance(wordNormalized, userNormalized)
                val locationDistance = locationDistance(wordResampled, userResampled)
                val shapeP = gaussian(shapeDistance, 0f, SHAPE_STD)
                val locationP = gaussian(locationDistance, 0f, std)
                val freqP = freqProbability(w.freq)
                val confidence = 1f / max(1e-12f, shapeP * locationP * freqP)
                insertSorted(best, w.value, confidence, maxCount)
            }
        }
        return best.map { it.first }
    }

    // ============================ 剪枝 ============================

    /** 起/终点各取最近 2 个键，命中 (首键,尾键) 词树 */
    private fun pruneByExtremities(): List<GlideWord> {
        val starts = nClosestKeys(gesture.firstX(), gesture.firstY(), 2)
        val ends = nClosestKeys(gesture.lastX(), gesture.lastY(), 2)
        if (starts.isEmpty() || ends.isEmpty()) return emptyList()

        val out = LinkedHashMap<String, GlideWord>()
        for (s in starts) {
            for (e in ends) {
                val list = wordsByEndpoint[pairKey(s, e)] ?: continue
                for (w in list) if (!out.containsKey(w.value)) out[w.value] = w
            }
        }
        return out.values.toList()
    }

    private fun nClosestKeys(x: Float, y: Float, n: Int): List<Char> {
        if (letterKeys.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Char, Float>>(letterKeys.size)
        for (k in letterKeys) scored.add(k.ch to distance(k.cx, k.cy, x, y))
        scored.sortBy { it.second }
        val out = ArrayList<Char>(n)
        for (p in scored) {
            if (out.size >= n) break
            out.add(p.first)
        }
        return out
    }

    private fun pruneByLength(words: List<GlideWord>): List<GlideWord> {
        val userLength = gesture.length()
        val threshold = PRUNING_LENGTH_THRESHOLD * radius
        val out = ArrayList<GlideWord>(words.size)
        for (w in words) {
            if (abs(userLength - idealLength(w.key)) < threshold) out.add(w)
        }
        return out
    }

    private fun idealLength(key: String): Float {
        idealLengthCache[key]?.let { return it }
        var len = 0f
        var prev: GlideKey? = null
        for (ch in key) {
            val k = keysByChar[ch] ?: continue
            val p = prev
            if (p != null) len += distance(p.cx, p.cy, k.cx, k.cy)
            prev = k
        }
        idealLengthCache[key] = len
        return len
    }

    // ============================ 理想路径 ============================

    /** 生成理想路径：平直版 + （出现重复字母时）绕环版 */
    private fun idealGestures(key: String): List<Gesture> {
        val plain = Gesture()
        val loop = Gesture()
        var prev = '\u0000'
        var hasLoop = false

        for (c in key) {
            val k = keysByChar[c] ?: continue
            if (prev == c) {
                val qx = k.w / 4f
                val qy = k.h / 4f
                loop.addPoint(k.cx + qx, k.cy + qy)
                loop.addPoint(k.cx + qx, k.cy - qy)
                loop.addPoint(k.cx - qx, k.cy - qy)
                loop.addPoint(k.cx - qx, k.cy + qy)
                hasLoop = true
                plain.addPoint(k.cx, k.cy)
            } else {
                plain.addPoint(k.cx, k.cy)
                loop.addPoint(k.cx, k.cy)
            }
            prev = c
        }
        return if (hasLoop) listOf(plain, loop) else listOf(plain)
    }

    // ============================ 打分 ============================

    private fun shapeDistance(a: Gesture, b: Gesture): Float {
        var total = 0f
        for (i in 0 until SAMPLING_POINTS) {
            total += distance(a.getX(i), a.getY(i), b.getX(i), b.getY(i))
        }
        return total
    }

    private fun locationDistance(a: Gesture, b: Gesture): Float {
        var total = 0f
        for (i in 0 until SAMPLING_POINTS) {
            total += abs(a.getX(i) - b.getX(i)) + abs(a.getY(i) - b.getY(i))
        }
        return total / SAMPLING_POINTS / 2f
    }

    private fun gaussian(value: Float, mean: Float, std: Float): Float {
        val s = max(0.0001f, std)
        val factor = 1.0 / (s * sqrt(2.0 * PI))
        val exponent = ((value - mean) / s).toDouble().pow(2.0)
        return (factor * exp(-0.5 * exponent)).toFloat().coerceAtLeast(1e-12f)
    }

    /** 词频概率（对数归一化到 0.05..1） */
    private fun freqProbability(freq: Int): Float {
        val f = max(1, freq).toFloat()
        val m = max(1, maxFreq).toFloat()
        return ((1f + ln(f)) / (1f + ln(m))).coerceIn(0.05f, 1f)
    }

    /** 按置信度升序插入并去重（与原实现一致，保留最优路径的那一方） */
    private fun insertSorted(
        list: MutableList<Pair<String, Float>>,
        value: String,
        confidence: Float,
        max: Int,
    ) {
        var idx = 0
        while (idx < list.size && list[idx].second <= confidence) idx++

        val dup = list.indexOfFirst { it.first == value }
        if (dup >= 0) {
            if (dup < idx) idx--
            list.removeAt(dup)
        }
        if (idx < max) list.add(idx, value to confidence)
        while (list.size > max) list.removeAt(list.size - 1)
    }

}

/** 两点欧氏距离（文件级，供 Gesture 与解码器共用） */
private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x1 - x2
    val dy = y1 - y2
    return sqrt(dx * dx + dy * dy)
}
