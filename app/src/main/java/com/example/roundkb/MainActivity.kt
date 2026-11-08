package com.example.roundkb

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import java.io.InputStream

/**
 * 安装引导 + 词库管理页：
 *  - 指导用户到系统设置开启输入法；
 *  - 自定义词库导入（文件选择器）、清空，导入即生效（应用内更新，无需重装）。
 */
class MainActivity : Activity() {

    private lateinit var tvStats: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStats = findViewById(R.id.tv_stats)

        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_import).setOnClickListener { pickDictFile() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            WordStore.get(this).clear()
            updateStats()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStats()
    }

    private fun updateStats() {
        tvStats.text = WordStore.get(this).info()
    }

    /** 用系统文件选择器选词库文本文件 */
    private fun pickDictFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/plain", "text/tab-separated-values", "text/csv", "application/octet-stream"))
        }
        startActivityForResult(i, REQ_PICK)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != REQ_PICK || res != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        val name = queryName(uri)
        try {
            val input: InputStream? = contentResolver.openInputStream(uri)
            if (input == null) {
                tvStats.text = "导入失败：无法打开文件"
                return
            }
            WordStore.get(this).importFrom(input, name)
            updateStats()
        } catch (e: Exception) {
            tvStats.text = "导入失败：${e.message}"
        }
    }

    private fun queryName(uri: Uri): String {
        var c: Cursor? = null
        return try {
            c = contentResolver.query(uri, null, null, null, null)
            if (c != null && c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) ?: "" else ""
            } else ""
        } catch (_: Exception) {
            ""
        } finally {
            c?.close()
        }
    }

    companion object {
        private const val REQ_PICK = 1
    }
}
