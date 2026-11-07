package com.example.roundkb;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 自定义词库（应用内可导入 / 更新 / 清空，无需重装应用）。
 *
 * 词库文件格式（UTF-8 文本，每行一条）：
 *   - 单词一行：            hello            → 输入 hel 联想 hello
 *   - 键 值（空格分隔）：    nihao 你好        → 输入 nihao 候选「你好」
 *   - 键 TAB 值 TAB 词频：  nihao<TAB>你好<TAB>500
 *   - # 开头为注释
 *
 * 匹配规则：按键序列（组合串）对 key 做前缀匹配，按词频优先返回候选值。
 * 因此既可以当英文联想词库，也可以当拼音→中文的自定义词库。
 */
public class WordStore {

    private static final int MAX_ENTRIES = 200000;   // 防止大词库撑爆内存
    private static final int MAX_MATCH_SCAN = 500;   // 单次前缀扫描上限

    private static class Entry {
        String key;
        String value;
        int freq;
    }

    private static WordStore sInstance;

    private final Context appContext;
    private final File dictFile;
    private Entry[] sorted = new Entry[0];  // 按 key 排序，用于前缀二分
    private int total = 0;
    private long cachedMtime = -1;
    private String sourceName = "";
    private long importTime = 0;

    public static synchronized WordStore get(Context c) {
        if (sInstance == null) sInstance = new WordStore(c.getApplicationContext());
        return sInstance;
    }

    private WordStore(Context c) {
        appContext = c.getApplicationContext();
        dictFile = new File(appContext.getFilesDir(), "custom_dict.txt");
        SharedPreferences sp = appContext.getSharedPreferences("dict_meta", 0);
        sourceName = sp.getString("source_name", "");
        importTime = sp.getLong("import_time", 0);
        load();
    }

    public boolean hasDictionary() { return total > 0; }

    public int entryCount() { return total; }

    public String info() {
        if (total == 0) return "当前词库：未导入（可导入单词表或 拼音→词 文本）";
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(importTime));
        return "当前词库：" + total + " 条 · 来源 " +
                (sourceName.isEmpty() ? "自定义" : sourceName) + " · 导入于 " + time;
    }

    /** 应用内更新词库：导入即生效（同进程），无需重装应用 */
    public void importFrom(InputStream in, String source) throws Exception {
        FileOutputStream out = new FileOutputStream(dictFile);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { out.close(); } catch (Exception ignore) {}
            try { in.close(); } catch (Exception ignore) {}
        }
        importTime = System.currentTimeMillis();
        sourceName = source == null ? "" : source;
        SharedPreferences sp = appContext.getSharedPreferences("dict_meta", 0);
        sp.edit().putString("source_name", sourceName)
                .putLong("import_time", importTime).apply();
        load();
    }

    public void clear() {
        dictFile.delete();
        appContext.getSharedPreferences("dict_meta", 0).edit().clear().apply();
        sourceName = "";
        importTime = 0;
        load();
    }

    /** 输入法侧检测词库文件是否被重新导入过（应用内更新后自动生效） */
    public void reloadIfChanged() {
        long mtime = dictFile.exists() ? dictFile.lastModified() : -1;
        if (mtime != cachedMtime) load();
    }

    private void load() {
        cachedMtime = dictFile.exists() ? dictFile.lastModified() : -1;
        List<Entry> list = new ArrayList<>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(dictFile), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null && list.size() < MAX_ENTRIES) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String key, value;
                int freq = 1;
                String[] parts = line.split("\t");
                if (parts.length >= 2) {
                    key = parts[0].trim().toLowerCase(Locale.ROOT);
                    value = parts[1].trim();
                    if (parts.length >= 3) {
                        try { freq = Integer.parseInt(parts[2].trim()); } catch (Exception ignore) {}
                    }
                } else {
                    String[] ws = line.split("\\s+");
                    if (ws.length >= 2) {
                        key = ws[0].toLowerCase(Locale.ROOT);
                        value = ws[1];
                        if (ws.length >= 3) {
                            try { freq = Integer.parseInt(ws[2]); } catch (Exception ignore) {}
                        }
                    } else {
                        key = line.toLowerCase(Locale.ROOT);
                        value = line;
                    }
                }

                if (key.isEmpty()) continue;
                if (value.isEmpty()) value = key;
                if (freq <= 0) freq = 1;
                Entry e = new Entry();
                e.key = key;
                e.value = value;
                e.freq = freq;
                list.add(e);
            }
        } catch (Exception ignore) {
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignore) {}
        }

        Collections.sort(list, (a, b) -> a.key.compareTo(b.key));
        sorted = list.toArray(new Entry[0]);
        total = sorted.length;
    }

    /** 前缀匹配候选：按词频优先、短 key 优先，返回去重后的候选值 */
    public List<String> prefix(String prefix, int limit) {
        List<String> out = new ArrayList<>();
        if (prefix == null || prefix.isEmpty() || total == 0) return out;
        String p = prefix.toLowerCase(Locale.ROOT);

        int lo = lowerBound(p);
        List<Entry> hits = new ArrayList<>();
        for (int i = lo; i < total && hits.size() < MAX_MATCH_SCAN; i++) {
            Entry e = sorted[i];
            if (!e.key.startsWith(p)) break;
            hits.add(e);
        }
        Collections.sort(hits, (a, b) -> {
            if (b.freq != a.freq) return b.freq - a.freq;
            return a.key.length() - b.key.length();
        });
        Set<String> seen = new HashSet<>();
        for (Entry e : hits) {
            if (seen.add(e.value)) {
                out.add(e.value);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    private int lowerBound(String p) {
        int lo = 0, hi = total;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid].key.compareTo(p) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
