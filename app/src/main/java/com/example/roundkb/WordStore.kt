package com.example.roundkb

import android.content.Context
import android.content.SharedPreferences
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max

/**
 * 自定义词库（应用内可导入 / 更新 / 清空，无需重装应用）。
 *
 * 词库文件格式（UTF-8 文本，每行一条，列序自动识别）：
 *  - 拼音 词 词频：   nihao<TAB>你好<TAB>500      （本应用标准格式）
 *  - 词 拼音：        你好<TAB>nihao              （搜狗 txt 导出等）
 *  - 词 拼音 词频：   你好<TAB>ni hao<TAB>500     （Rime 词典数据行，拼音可含空格）
 *  - 词 词频 词性：   你好<TAB>500<TAB>r          （jieba 词典——无拼音，自动跳过）
 *  - 英文词：         hello / hello<TAB>world<TAB>500
 *  - 拼音带声调自动剥除：ni3 hao3、nǐ hǎo 均可
 *  - # 开头为注释；Rime dict.yaml 的元数据头自动跳过
 *
 * 匹配规则：按键序列（组合串）对 key 做前缀匹配，按词频优先返回候选值；
 * 滑行输入对 key 做首尾字母过滤后交给路径打分。
 */
class WordStore private constructor(context: Context) {

    private class Entry(val key: String, val normKey: String, val value: String, val freq: Int)

    private val appContext = context.applicationContext
    private val dictFile = File(appContext.filesDir, "custom_dict.txt")
    private val userFile = File(appContext.filesDir, "user_dict.txt")

    @Volatile private var sorted: List<Entry> = emptyList()  // 按 key 升序，前缀二分用
    private var cachedMtime = -1L
    private var sourceName = ""
    private var importTime = 0L

    /** key → 词条 索引（整句解码用，键为模糊音归一形），词库加载后延迟构建 */
    @Volatile private var keyIndex: HashMap<String, ArrayList<Entry>>? = null
    @Volatile private var keyIndexFuzzy: Boolean? = null

    private fun ensureKeyIndex(): HashMap<String, ArrayList<Entry>>? {
        val wantFuzzy = SettingsStore.fuzzy(appContext)
        val cur = keyIndex
        if (cur != null && keyIndexFuzzy == wantFuzzy) return cur
        synchronized(this) {
            val c2 = keyIndex
            if (c2 != null && keyIndexFuzzy == wantFuzzy) return c2
            if (sorted.isEmpty()) return null
            val m = HashMap<String, ArrayList<Entry>>(sorted.size * 2)
            for (e in sorted) m.getOrPut(e.normKey) { ArrayList(2) }.add(e)
            keyIndex = m
            keyIndexFuzzy = wantFuzzy
            return m
        }
    }

    init {
        val sp = appContext.getSharedPreferences("dict_meta", 0)
        sourceName = sp.getString("source_name", "") ?: ""
        importTime = sp.getLong("import_time", 0L)
        load()
    }

    val hasDictionary: Boolean get() = sorted.isNotEmpty()
    val entryCount: Int get() = sorted.size

    fun info(): String {
        if (sorted.isEmpty()) return "当前词库：未导入（可导入单词表或 拼音→词 文本）"
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(importTime))
        return "当前词库：${sorted.size} 条 · 来源 ${sourceName.ifEmpty { "自定义" }} · 导入于 $time"
    }

    /** 应用内更新词库：导入即生效（同进程），无需重装应用。
     *  二进制/压缩词库（.scel、zip 包裹的 .dict 等）由 DictConverter 即时转换为内部格式。 */
    fun importFrom(input: InputStream, source: String?) {
        val raw = input.readBytes()
        try { input.close() } catch (_: Exception) {}
        val converted = source?.let { DictConverter.convertToText(raw, it) }
        FileOutputStream(dictFile).use { out ->
            if (converted != null) out.write(converted.toByteArray(Charsets.UTF_8))
            else out.write(raw)
        }
        importTime = System.currentTimeMillis()
        sourceName = source ?: ""
        appContext.getSharedPreferences("dict_meta", 0).edit()
            .putString("source_name", sourceName)
            .putLong("import_time", importTime)
            .apply()
        load()
    }

    fun clear() {
        dictFile.delete()
        userFile.delete()
        synchronized(userEntries) { userEntries.clear() }
        appContext.getSharedPreferences("dict_meta", 0).edit().clear().apply()
        sourceName = ""
        importTime = 0L
        load()
    }

    /** 输入法侧检测词库文件是否被重新导入过（应用内更新后自动生效） */
    fun reloadIfChanged() {
        val mtime = if (dictFile.exists()) dictFile.lastModified() else -1L
        if (mtime != cachedMtime) load()
    }

    /** 模糊音开关变化后重建索引与归一键（可后台线程调用） */
    fun reloadFuzzy() = load()

    private fun load() {
        cachedMtime = if (dictFile.exists()) dictFile.lastModified() else -1L
        val list = mutableListOf<Entry>()
        try {
            BufferedReader(InputStreamReader(dictFile.inputStream(), Charsets.UTF_8)).use { br ->
                var line = br.readLine()
                while (line != null && list.size < MAX_ENTRIES) {
                    parseLine(line.trim())?.let { list.add(it) }
                    line = br.readLine()
                }
            }
        } catch (_: Exception) {
        }
        // 合并用户词典（自学习的高频词覆盖主库频次）
        if (userFile.exists()) {
            try {
                BufferedReader(InputStreamReader(userFile.inputStream(), Charsets.UTF_8)).use { br ->
                    var line = br.readLine()
                    while (line != null && list.size < MAX_ENTRIES) {
                        parseLine(line.trim())?.let { e ->
                            list.removeAll { it.normKey == e.normKey && it.value == e.value }
                            list.add(e)
                        }
                        line = br.readLine()
                    }
                }
            } catch (_: Exception) {
            }
        }
        sorted = list.sortedBy { it.normKey }
        keyIndex = null   // 词库变了，整句索引重建
        endpointIndex = null   // 词库变了，端点索引重建
        // 后台预构建整句索引，避免首次按键卡顿
        Thread { try { ensureKeyIndex() } catch (_: Exception) {} }.start()
    }

    // ==================== 用户词典自学习 ====================

    /** 用户上屏过的词（独立文件，导入新词库不丢失），freq 已含加权 */
    @Volatile private var userEntries = mutableListOf<Entry>()

    /**
     * 自学习：用户选定某候选后调用，提高该词词频、下次优先出。
     * 整句上屏同样适用（整句按全拼整体成词，下次同串直接首选）。
     */
    fun learn(pinyin: String, text: String) {
        val p = normPinyin(pinyin)
        if (p.isEmpty() || text.isEmpty()) return
        val nk = normFuzzy(p)
        synchronized(userEntries) {
            userEntries.removeAll { it.normKey == nk && it.value == text }
            val base = sorted.firstOrNull { it.normKey == nk && it.value == text }?.freq ?: 0
            userEntries.add(Entry(nk, nk, text, max(base + 50, 10000)))
        }
        Thread {
            try {
                synchronized(userEntries) {
                    userFile.bufferedWriter(Charsets.UTF_8).use { w ->
                        for (e in userEntries) w.write("${e.normKey}\t${e.value}\t${e.freq}\n")
                    }
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    /** 拼音归一化：小写、剥声调符号（nǐ→ni）与音节尾声调数字（ni3→ni），只留字母 */
    private fun normPinyin(s: String): String {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        for (ch in nfd) {
            val c = Character.toLowerCase(ch)
            if (c in 'a'..'z') sb.append(c)
            // 其余（声调 combining mark、数字、空格）一律丢弃
        }
        return sb.toString()
    }

    /** 模糊音归一（可开关）：zh/z ch/c sh/s；ian/iang uan/uang an/ang en/eng in/ing */
    private fun normFuzzy(s: String): String {
        if (!SettingsStore.fuzzy(appContext)) return s
        return s.replace("zh", "z").replace("ch", "c").replace("sh", "s")
            .replace("iang", "ian").replace("uang", "uan")
            .replace("ang", "an").replace("eng", "en").replace("ing", "in")
    }

    private fun makeEntry(key: String, value: String, freq: Int) =
        Entry(key, normFuzzy(normPinyin(key)), value, freq)

    private fun isHanCol(s: String): Boolean {
        for (ch in s) {
            if (ch in '\u4e00'..'\u9fff' ||          // CJK 基本区
                ch in '\u3400'..'\u4dbf' ||          // 扩展 A 区（㞎 等）
                ch in '\ud800'..'\udbff'             // 增补平面（𩽾 等生僻字，代理对首字节）
            ) return true
        }
        return false
    }

    /** 列自动识别解析：兼容本应用格式 / 搜狗 txt / Rime 词典 / jieba 词典 / 英文词表 */
    private fun parseLine(line: String): Entry? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) return null
        if (t == "---" || t == "...") return null                       // Rime yaml 分隔
        if (t.contains('\uFFFD')) return null                           // 二进制词库（.scel 等）乱码
        if (!isHanCol(t) && t.contains(':')) return null                // yaml 元数据 name: xxx

        val cols = t.split(Regex("[\\t ]+")).filter { it.isNotEmpty() }
        if (cols.isEmpty()) return null

        val pinyinRegex = Regex("^[A-Za-züÜ]+[1-5]?$")
        val hanCols = mutableListOf<String>()
        val alphaCols = mutableListOf<String>()     // 出现在首个数字列之前的字母列
        var freq = 1
        var seenNum = false
        for (c in cols) {
            when {
                isHanCol(c) -> hanCols.add(c)
                c.all { it.isDigit() } -> {
                    val f = c.toIntOrNull()
                    if (!seenNum && f != null && f > 0) { freq = f; seenNum = true }
                    // 数字列之后出现的字母列一律视为词性等标注，不再当拼音
                }
                // 纯字母列（含带声调的 nǐ / ü 等；normPinyin 统一剥声调）
                c.all { it.isLetter() } || pinyinRegex.matches(c) ->
                    if (!seenNum) alphaCols.add(c)
                // 其它（词性后缀、bin 字段等）忽略
            }
        }

        var key: String
        var value: String
        if (hanCols.isNotEmpty()) {
            value = hanCols.first()
            key = normPinyin(alphaCols.joinToString(""))   // Rime "ni hao" → "nihao"
            if (key.isEmpty()) return null                 // 有词无拼音：无法按键检索，跳过
        } else {
            // 纯英文行：hello → key=value；hello world → key=hello value=world
            when (alphaCols.size) {
                1 -> { key = alphaCols[0].lowercase(Locale.ROOT); value = key }
                in 2..Int.MAX_VALUE -> {
                    key = alphaCols[0].lowercase(Locale.ROOT)
                    value = alphaCols[1]
                }
                else -> return null
            }
        }
        if (key.isEmpty()) return null
        if (value.isEmpty()) value = key
        return makeEntry(key, value, freq)
    }

    /** 前缀匹配候选：按词频优先、短 key 优先，返回去重后的候选值（支持模糊音） */
    fun prefix(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty() || sorted.isEmpty()) return emptyList()
        val p = normFuzzy(normPinyin(prefix))
        val hits = mutableListOf<Entry>()
        var i = lowerBound(p)
        while (i < sorted.size && hits.size < MAX_MATCH_SCAN) {
            val e = sorted[i]
            if (!e.normKey.startsWith(p)) break
            hits.add(e)
            i++
        }
        hits.sortWith(compareByDescending<Entry> { it.freq }.thenBy { it.key.length })
        val out = LinkedHashSet<String>()
        for (e in hits) {
            out.add(e.value)
            if (out.size >= limit) break
        }
        return out.toList()
    }

    // ==================== 整句拼音解码 ====================

    private val syllablesByFirst: Map<Char, List<String>> =
        PinyinSyllables.ALL.groupBy({ it[0] }, { it })
            .mapValues { (_, v) -> v.sortedByDescending { it.length } }

    private val WORD_BONUS = 12.0    // 每多一个音节的词长奖励（长词优先，抑制碎句）
    private val SINGLE_PEN = 0.7     // 单字词惩罚（高频单字连打不应压过整词）
    private val SENT_BEAM = 10       // beam 宽度

    /**
     * 整句拼音：连续按键串（如 "nihaoshijie"）→ 自动分词组句（"你好世界"）。
     *
     * 算法：音节切分 DAG + beam 搜索。
     *  1. 用 [PinyinSyllables] 把输入串切成音节 DAG（xian / xi'an 等歧义全部保留）；
     *  2. 从每个切分点枚举「连续若干音节组成的段」，段串在词库 key 索引中精确命中的即为词边；
     *  3. beam 维特比：边权 = ln(词频) + 词长奖励，全局保留 [SENT_BEAM] 条路径；
     *  4. 到达串尾的路径按得分排序，返回 top-limit 个整句（词直接相连，无分隔符）。
     *
     * 纯 uni-gram 词频模型（词库无接续统计），日常短句效果良好；长句质量受词库覆盖影响。
     */
    fun sentence(input: String, limit: Int): List<String> {
        val p = input.lowercase(Locale.ROOT)
        if (p.isEmpty() || p.length > 48 || sorted.isEmpty()) return emptyList()
        val idx = ensureKeyIndex() ?: return emptyList()

        class Path(val score: Double, val text: String, val pos: Int)

        // 从 start 起 DFS 枚举所有「音节连续段」：每个在 key 索引中命中的段生成一条词边
        fun collectEdges(start: Int, out: ArrayList<Triple<Int, Double, String>>) {
            fun dfs(pos: Int, acc: StringBuilder, sylCount: Int) {
                if (pos >= p.length || sylCount >= 8 || acc.length >= 24) return
                val group = syllablesByFirst[p[pos]] ?: return
                for (s in group) {
                    if (!p.startsWith(s, pos)) continue
                    acc.append(s)
                    val entries = idx[normFuzzy(acc.toString())]
                    if (entries != null) {
                        val best = entries.maxByOrNull { it.freq }!!
                        var base = lnFreq(best.freq)
                        if (best.value.length == 1) base *= SINGLE_PEN
                        out.add(Triple(pos + s.length, base + WORD_BONUS * sylCount, best.value))
                    }
                    dfs(pos + s.length, acc, sylCount + 1)
                    acc.setLength(acc.length - s.length)
                }
            }
            dfs(start, StringBuilder(), 0)
        }

        var beam = ArrayList<Path>(); beam.add(Path(0.0, "", 0))
        val finished = ArrayList<Pair<Double, String>>()
        var guard = 0
        while (beam.isNotEmpty() && guard++ < 24) {
            val next = ArrayList<Path>(beam.size * 8)
            for (path in beam) {
                if (path.pos >= p.length) {
                    finished.add(path.score to path.text)
                    continue
                }
                val edges = ArrayList<Triple<Int, Double, String>>(8)
                collectEdges(path.pos, edges)
                for ((npos, w, word) in edges) {
                    next.add(Path(path.score + w, path.text + word, npos))
                }
            }
            if (next.isEmpty()) break
            next.sortByDescending { it.score }
            if (next.size > SENT_BEAM) next.subList(SENT_BEAM, next.size).clear()
            beam = next
        }
        finished.sortByDescending { it.first }
        val out = LinkedHashSet<String>()
        for ((_, s) in finished) {
            out.add(s)
            if (out.size >= limit) break
        }
        return out.toList()
    }

    // ==================== 九键 T9 解码 ====================

    /** T9 数字键字母组（下标 = 数字） */
    private val DIGIT_LETTERS = arrayOf(
        "", "", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")

    /** 拼音字母 → 所属数字键 */
    private val digitOf: Map<Char, Char> = buildMap {
        for (d in '2'..'9') for (c in DIGIT_LETTERS[d - '0']) put(c, d)
    }

    /** 按首个字母所在数字分组的音节表（长度降序，整句切分用） */
    private val syllablesByDigit: Map<Char, List<String>> =
        PinyinSyllables.ALL.groupBy { digitOf[it[0]]!! }
            .mapValues { (_, v) -> v.sortedByDescending { it.length } }

    private val syllableSet = HashSet<String>(PinyinSyllables.ALL.size * 2)
    private val syllablePrefixSet = HashSet<String>(PinyinSyllables.ALL.size * 6)

    init {
        for (s in PinyinSyllables.ALL) {
            syllableSet.add(s)
            for (i in 1..s.length) syllablePrefixSet.add(s.substring(0, i))
        }
    }

    /** 音节 s 是否与数字串 digits 从 pos 起逐位匹配（字母都属于对应数字键） */
    private fun matchesDigits(s: String, digits: String, pos: Int): Boolean {
        if (pos + s.length > digits.length) return false
        for (i in s.indices) {
            if (digitOf[s[i]] != digits[pos + i]) return false
        }
        return true
    }

    /**
     * 数字串 → 所有可能的拼音串（每个音节段必须合法或为合法音节前缀）。
     * DP：状态 =（已消耗位数, 当前音节前缀），状态内保留全部代表串（不同串对应不同词），
     * 总量超上限时截断防爆炸。
     */
    private fun t9Expansions(d: String): List<String> {
        if (d.isEmpty() || d.length > 12 || d.any { it < '2' || it > '9' }) return emptyList()
        var states = LinkedHashMap<String, MutableSet<String>>()
        for (c in DIGIT_LETTERS[d[0] - '0']) {
            if (c.toString() in syllablePrefixSet)
                states.putIfAbsent(c.toString(), LinkedHashSet(listOf(c.toString())))
        }
        for (i in 1 until d.length) {
            val next = LinkedHashMap<String, MutableSet<String>>()
            for (c in DIGIT_LETTERS[d[i] - '0']) {
                for ((syl, accs) in states) {
                    val s2 = syl + c
                    if (s2 in syllablePrefixSet) {
                        val set = next.getOrPut(s2) { LinkedHashSet() }
                        for (a in accs) set.add(a + c)
                    }
                    // 当前音节已完整 → 可作音节边界，c 开新音节
                    if (syl in syllableSet && c.toString() in syllablePrefixSet) {
                        val set = next.getOrPut(c.toString()) { LinkedHashSet() }
                        for (a in accs) set.add(a + c)
                    }
                }
            }
            if (next.isEmpty()) return emptyList()
            states = next
        }
        val out = LinkedHashSet<String>()
        for ((_, accs) in states) {
            for (a in accs) {
                out.add(a)
                if (out.size >= T9_EXP_MAX) return out.toList()
            }
        }
        return out.toList()
    }

    /** 九键词级候选：数字串 → 拼音串展开 → 词库前缀匹配（按词频优先合并） */
    fun t9Candidates(digits: String, limit: Int): List<String> {
        if (digits.isEmpty() || sorted.isEmpty() || limit <= 0) return emptyList()
        val out = LinkedHashSet<String>()
        for (e in t9Expansions(digits)) {
            for (w in prefix(e, limit - out.size)) {
                out.add(w)
                if (out.size >= limit) return out.toList()
            }
        }
        return out.toList()
    }

    /**
     * 九键整句解码：数字串（如 "6426424926"）→ 自动分词组句。
     * 与 [sentence] 同一 beam 维特比框架，仅音节切分受数字键约束。
     */
    fun t9Sentence(digits: String, limit: Int): List<String> {
        if (digits.isEmpty() || digits.length > 32 || sorted.isEmpty()) return emptyList()
        val idx = ensureKeyIndex() ?: return emptyList()

        class Path(val score: Double, val text: String, val pos: Int)

        fun collectEdges(start: Int, out: ArrayList<Triple<Int, Double, String>>) {
            fun dfs(pos: Int, acc: StringBuilder, sylCount: Int) {
                if (pos >= digits.length || sylCount >= 8 || acc.length >= 24) return
                val group = syllablesByDigit[digits[pos]] ?: return
                for (s in group) {
                    if (!matchesDigits(s, digits, pos)) continue
                    acc.append(s)
                    val entries = idx[normFuzzy(acc.toString())]
                    if (entries != null) {
                        val best = entries.maxByOrNull { it.freq }!!
                        var base = lnFreq(best.freq)
                        if (best.value.length == 1) base *= SINGLE_PEN
                        out.add(Triple(pos + s.length, base + WORD_BONUS * sylCount, best.value))
                    }
                    dfs(pos + s.length, acc, sylCount + 1)
                    acc.setLength(acc.length - s.length)
                }
            }
            dfs(start, StringBuilder(), 0)
        }

        var beam = ArrayList<Path>(); beam.add(Path(0.0, "", 0))
        val finished = ArrayList<Pair<Double, String>>()
        var guard = 0
        while (beam.isNotEmpty() && guard++ < 24) {
            val next = ArrayList<Path>(beam.size * 8)
            for (path in beam) {
                if (path.pos >= digits.length) {
                    finished.add(path.score to path.text)
                    continue
                }
                val edges = ArrayList<Triple<Int, Double, String>>(8)
                collectEdges(path.pos, edges)
                for ((npos, w, word) in edges) {
                    next.add(Path(path.score + w, path.text + word, npos))
                }
            }
            if (next.isEmpty()) break
            next.sortByDescending { it.score }
            if (next.size > SENT_BEAM) next.subList(SENT_BEAM, next.size).clear()
            beam = next
        }
        finished.sortByDescending { it.first }
        val out = LinkedHashSet<String>()
        for ((_, s) in finished) {
            out.add(s)
            if (out.size >= limit) break
        }
        return out.toList()
    }

    private fun lnFreq(f: Int): Double = ln(max(1, f).toDouble())

    /**
     * 端点索引：`(首字母 << 16 | 尾字母) → 词`。
     *
     * 与键盘布局无关（只依赖 a-z），因此词库变动时构建一次即可，
     * 布局重排（候选行出现/消失）不会触发重建。构建过程按 value 去重、
     * 保留最高词频，供 [GlideDecoder] 做端点剪枝。
     */
    @Volatile private var endpointIndex: Map<Long, List<GlideWord>>? = null
    @Volatile private var maxWordFreq: Int = 1

    val maxFrequency: Int
        get() {
            ensureEndpointIndex()
            return maxWordFreq
        }

    fun endpointIndex(): Map<Long, List<GlideWord>> {
        ensureEndpointIndex()
        return endpointIndex ?: emptyMap()
    }

    private fun ensureEndpointIndex() {
        if (endpointIndex != null) return
        synchronized(this) {
            if (endpointIndex != null) return
            val byValue = HashMap<String, GlideWord>(sorted.size)
            var mf = 1
            for (e in sorted) {
                val k = e.key
                if (k.length < 2 || !isAsciiWord(k)) continue
                if (e.freq > mf) mf = e.freq
                val cur = byValue[e.value]
                if (cur == null || cur.freq < e.freq) {
                    byValue[e.value] = GlideWord(k, e.value, e.freq)
                }
            }
            val map = HashMap<Long, MutableList<GlideWord>>(Math.max(64, byValue.size / 8))
            for (w in byValue.values) {
                val k = GlideDecoder.pairKey(w.key[0], w.key[w.key.length - 1])
                map.getOrPut(k) { ArrayList(16) }.add(w)
            }
            maxWordFreq = mf
            endpointIndex = map
        }
    }

    private fun isAsciiWord(s: String): Boolean {
        for (c in s) if (c < 'a' || c > 'z') return false
        return true
    }

    private fun lowerBound(p: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].normKey < p) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private const val MAX_ENTRIES = 200000   // 防止大词库撑爆内存
        private const val MAX_MATCH_SCAN = 500   // 单次前缀扫描上限
        private const val T9_EXP_MAX = 400       // 九键拼音展开上限

        @Volatile private var instance: WordStore? = null
        fun get(c: Context): WordStore =
            instance ?: synchronized(this) {
                instance ?: WordStore(c).also { instance = it }
            }
    }
}
