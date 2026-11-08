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
import java.util.Locale

/**
 * 自定义词库（应用内可导入 / 更新 / 清空，无需重装应用）。
 *
 * 词库文件格式（UTF-8 文本，每行一条）：
 *  - 单词一行：           hello            → 输入 hel 联想 hello
 *  - 键 值（空格分隔）：   nihao 你好        → 输入 nihao 候选「你好」
 *  - 键 TAB 值 TAB 词频： nihao<TAB>你好<TAB>500
 *  - # 开头为注释
 *
 * 匹配规则：按键序列（组合串）对 key 做前缀匹配，按词频优先返回候选值；
 * 滑行输入对 key 做首尾字母过滤后交给路径打分。
 */
class WordStore private constructor(context: Context) {

    private class Entry(val key: String, val value: String, val freq: Int)

    private val appContext = context.applicationContext
    private val dictFile = File(appContext.filesDir, "custom_dict.txt")

    @Volatile private var sorted: List<Entry> = emptyList()  // 按 key 升序，前缀二分用
    private var cachedMtime = -1L
    private var sourceName = ""
    private var importTime = 0L

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

    /** 应用内更新词库：导入即生效（同进程），无需重装应用 */
    fun importFrom(input: InputStream, source: String?) {
        FileOutputStream(dictFile).use { out ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        try { input.close() } catch (_: Exception) {}
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
        sorted = list.sortedBy { it.key }
    }

    private fun parseLine(line: String): Entry? {
        if (line.isEmpty() || line.startsWith("#")) return null
        val parts = line.split("\t")
        var key: String
        var value: String
        var freq = 1
        if (parts.size >= 2) {
            key = parts[0].trim().lowercase(Locale.ROOT)
            value = parts[1].trim()
            if (parts.size >= 3) freq = parts[2].trim().toIntOrNull() ?: 1
        } else {
            val ws = line.split(Regex("\\s+"))
            if (ws.size >= 2) {
                key = ws[0].lowercase(Locale.ROOT)
                value = ws[1]
                if (ws.size >= 3) freq = ws[2].toIntOrNull() ?: 1
            } else {
                key = line.lowercase(Locale.ROOT)
                value = line
            }
        }
        if (key.isEmpty()) return null
        if (value.isEmpty()) value = key
        if (freq <= 0) freq = 1
        return Entry(key, value, freq)
    }

    /** 前缀匹配候选：按词频优先、短 key 优先，返回去重后的候选值 */
    fun prefix(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty() || sorted.isEmpty()) return emptyList()
        val p = prefix.lowercase(Locale.ROOT)
        val hits = mutableListOf<Entry>()
        var i = lowerBound(p)
        while (i < sorted.size && hits.size < MAX_MATCH_SCAN) {
            val e = sorted[i]
            if (!e.key.startsWith(p)) break
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

    /** 滑行输入过滤：首字母相同 + 尾字母相同的词（cap 防止超长词库拖慢打分） */
    fun gestureFilter(first: String, last: String, cap: Int): List<String> {
        val out = LinkedHashSet<String>()
        var i = lowerBound(first)
        while (i < sorted.size && out.size < cap) {
            val e = sorted[i]
            if (!e.key.startsWith(first)) break
            if (e.key.length >= 2 && e.key.endsWith(last)) out.add(e.value)
            i++
        }
        return out.toList()
    }

    private fun lowerBound(p: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].key < p) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private const val MAX_ENTRIES = 200000   // 防止大词库撑爆内存
        private const val MAX_MATCH_SCAN = 500   // 单次前缀扫描上限

        @Volatile private var instance: WordStore? = null
        fun get(c: Context): WordStore =
            instance ?: synchronized(this) {
                instance ?: WordStore(c).also { instance = it }
            }
    }
}
