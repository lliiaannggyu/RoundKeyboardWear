package com.example.roundkb

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 词库转换器：把市面上流通的词库格式在导入时即时转换为内部标准文本格式
 * （拼音 TAB 词 TAB 词频），导入一次、永久可用，无需外部转换工具。
 *
 * 支持：
 *  - 搜狗细胞词库 .scel（市面流通量最大的词库格式，直接解析二进制）
 *  - zip 包裹的文本词库（谷歌拼音 .dict、zip 内 txt / Rime yaml 等，自动解包）
 *  - 其余格式交回 WordStore 的文本列自动识别（搜狗 txt 导出 / Rime / jieba / 英文词表）
 *
 * 不支持：百度 .bdict、QQ 拼音 .qpyd 等压缩加密格式（请先用"深蓝词库转换"转 txt）。
 */
object DictConverter {

    private const val MAX_SIZE = 96 * 1024 * 1024   // 输入上限，防异常大文件撑爆内存

    /** 返回内部标准格式文本；无法识别时返回 null（调用方按纯 UTF-8 文本处理） */
    fun convertToText(data: ByteArray, fileName: String): String? {
        if (data.size > MAX_SIZE) return null
        return when {
            isScel(data) || fileName.endsWith(".scel", true) -> parseScel(data)
            isZip(data) -> parseZip(data)
            else -> null
        }
    }

    private fun isZip(d: ByteArray) =
        d.size > 4 && d[0] == 'P'.code.toByte() && d[1] == 'K'.code.toByte() &&
            d[2] == 3.toByte() && d[3] == 4.toByte()

    /** scel 魔数 "DCS\x01"（新格式在 0x04，旧格式在 0x08） */
    private fun isScel(d: ByteArray): Boolean {
        if (d.size < 16) return false
        val m = { o: Int -> d[o] == 'D'.code.toByte() && d[o + 1] == 'C'.code.toByte() &&
            d[o + 2] == 'S'.code.toByte() && d[o + 3] == 1.toByte() }
        return m(4) || m(8)
    }

    // ---------- 搜狗 .scel 二进制解析 ----------

    private fun u16(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)

    private fun i32(d: ByteArray, off: Int): Int =
        (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8) or
            ((d[off + 2].toInt() and 0xFF) shl 16) or ((d[off + 3].toInt() and 0xFF) shl 24)

    /**
     * scel 结构（经真实词库样本逆向验证）：
     *  - 0x00：4B = 拼音表偏移；0x04：魔数 DCS\x01
     *  - 拼音表：4B 数量，之后每项「2B 序号 + 2B 字节长 + UTF-16LE 拼音」
     *  - 词表（紧随拼音表，恰为旧文档常量 0x2628）：每项
     *    「2B 同音数 + 2B 拼音序号区字节长 + 序号×2B + 2B 词字节长 + UTF-16LE 词 + 12B 尾块」
     *  - 词库无可靠词频（尾块恒定），词频记 1，靠词库内序保序
     */
    private fun parseScel(d: ByteArray): String? {
        if (d.size < 0x2000) return null
        return try {
            val pyOff = i32(d, 0)
            if (pyOff <= 8 || pyOff >= d.size - 8) return null

            val pyCount = i32(d, pyOff)
            if (pyCount <= 0 || pyCount > 2048) return null
            val pyMap = HashMap<Int, String>(pyCount * 2)
            var p = pyOff + 4
            for (i in 0 until pyCount) {
                if (p + 4 > d.size) break
                val idx = u16(d, p); p += 2
                val len = u16(d, p); p += 2
                if (len <= 0 || p + len > d.size) return null
                pyMap[idx] = String(d, p, len, Charsets.UTF_16LE)
                p += len
            }
            if (pyMap.isEmpty()) return null

            val sb = StringBuilder(1 shl 16)
            var q = p
            while (q + 8 <= d.size) {
                val same = u16(d, q)
                val pyLen = u16(d, q + 2)
                if (same !in 1..16 || pyLen !in 2..60 || q + 4 + pyLen + 4 > d.size) break
                var q2 = q + 4
                val pys = StringBuilder(pyLen * 2)
                var bad = false
                for (i in 0 until pyLen / 2) {
                    val py = pyMap[u16(d, q2)]
                    if (py == null) { bad = true; break }
                    pys.append(py); q2 += 2
                }
                if (bad) break
                if (q2 + 2 > d.size) break
                val wLen = u16(d, q2); q2 += 2
                if (wLen <= 0 || q2 + wLen > d.size) break
                val word = String(d, q2, wLen, Charsets.UTF_16LE)
                q2 += wLen
                if (q2 + 12 > d.size) break
                q = q2 + 12   // 尾块（无词频意义）跳过
                if (word.any { it in '\u4e00'..'\u9fff' } || word.all { it.isLetter() }) {
                    sb.append(pys.toString().lowercase()).append('\t')
                        .append(word).append("\t1\n")
                }
            }
            if (sb.isEmpty()) null else sb.toString()
        } catch (_: Exception) {
            null
        }
    }

    // ---------- zip 包裹的文本词库 ----------

    /** 解包 zip，取第一个非目录条目递归转换；内层是普通文本则按 UTF-8 返回 */
    private fun parseZip(data: ByteArray): String? = try {
        ZipInputStream(ByteArrayInputStream(data)).use { z ->
            var entry = z.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = z.readBytes()
                    val inner = convertToText(bytes, entry.name)
                    return inner ?: String(bytes, Charsets.UTF_8)
                }
                entry = z.nextEntry
            }
            null
        }
    } catch (_: Exception) {
        null
    }
}
