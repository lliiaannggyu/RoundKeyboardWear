package com.example.roundkb;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import java.io.InputStream;

/**
 * 安装引导 + 词库管理页：
 *  - 指导用户到系统设置开启输入法；
 *  - 自定义词库导入（文件选择器）、清空，导入即生效（应用内更新，无需重装）。
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;

    private TextView tvStats;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStats = findViewById(R.id.tv_stats);

        Button btnEnable = findViewById(R.id.btn_enable);
        btnEnable.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));

        Button btnImport = findViewById(R.id.btn_import);
        btnImport.setOnClickListener(v -> pickDictFile());

        Button btnClear = findViewById(R.id.btn_clear);
        btnClear.setOnClickListener(v -> {
            WordStore.get(this).clear();
            updateStats();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        updateStats();
    }

    private void updateStats() {
        tvStats.setText(WordStore.get(this).info());
    }

    /** 用系统文件选择器选词库文本文件（ Wear OS 上可用手机伴侣端协同选择） */
    private void pickDictFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain", "text/tab-separated-values", "text/csv", "application/octet-stream"});
        startActivityForResult(i, REQ_PICK);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = queryName(uri);
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            WordStore.get(this).importFrom(in, name);
            updateStats();
        } catch (Exception e) {
            tvStats.setText("导入失败：" + e.getMessage());
        }
    }

    private String queryName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignore) {
        } finally {
            if (c != null) c.close();
        }
        return "";
    }
}
