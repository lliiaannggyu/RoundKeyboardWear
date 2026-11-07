package com.example.roundkb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 圆屏专用 26 键键盘视图。
 *
 * 排布规则（专为圆形表盘设计，不是方屏里画圆）：
 *  - 所有按键中心沿「圆弧行」分布，弧度与行到圆心的距离一致，
 *    使每一排都贴合表盘同心圆，左右两端自然收进圆内；
 *  - 整个键盘被限制在「安全圆」内：安全圆 = 屏幕内切圆 - 外边距，
 *    任何按键外缘越界都会被拉回，保证不贴表盘边框；
 *  - 按键按「离圆心最近命中」方式处理触点，圆弧排布下触控手感均匀。
 *
 * 词库 / 组合输入：
 *  - 已导入词库时，字母先进入组合串，候选词沿表盘最上方的弧排布（最多 3 个）；
 *  - 空格 = 上屏首选候选（无候选则上屏原始组合串）+ 空格；
 *  - 回车 = 上屏原始组合串；退格优先删除组合串；
 *  - 未导入词库时保持纯字母直通上屏。
 */
public class RoundKeyboardView extends View {

    public interface KeyListener {
        void onText(String text);
        void onAction(int action);
    }

    public static final int ACTION_SHIFT  = 1;
    public static final int ACTION_DELETE = 2;
    public static final int ACTION_ENTER  = 3;
    public static final int ACTION_MODE   = 4;

    // ================== 可调参数（按表盘大小微调） ==================
    /** 键盘外边距占屏幕最小边的比例（边距限制 1：不贴表盘） */
    private static final float MARGIN_FRACTION = 0.055f;
    /** 边距下限（px），小表盘上也不至于贴边 */
    private static final float MIN_MARGIN_PX = 10f;
    /** 按键外缘距屏幕圆周的安全系数（边距限制 2：越界自动拉回） */
    private static final float SAFE_FACTOR = 0.955f;
    /** 触摸判定半径放大倍数（圆屏手指容差） */
    private static final float TOUCH_SLOP = 1.55f;

    private static final String[] ROW0 = {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"};
    private static final String[] ROW1 = {"A", "S", "D", "F", "G", "H", "J", "K", "L"};
    private static final String[] ROW2 = {"Z", "X", "C", "V", "B", "N", "M"};

    private static final String[] SYM0 = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
    private static final String[] SYM1 = {"@", "#", "￥", "%", "&", "*", "-", "+", "/"};
    private static final String[] SYM2 = {".", ",", "?", "!", "'", "\"", ":"};

    // 有候选词时的压缩版行位置（为顶部候选弧腾出空间）
    private static final float CAND_Y = -0.62f;   // 候选弧纵向位置
    private static final float ROW0_Y_WITH_CAND = -0.34f;
    private static final float ROW1_Y_WITH_CAND = -0.02f;
    private static final float ROW2_Y_WITH_CAND = 0.26f;

    // ============================== 按键 ==============================
    private static class Key {
        String label;   // 显示
        String text;    // 上屏文本（控制键为 null）
        int action = -1;
        int candIndex = -1;   // >=0 表示候选词按键
        float x, y, r;
        boolean control;
        boolean sticky;
    }

    private final List<Key> keys = new ArrayList<>();

    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private KeyListener listener;
    private Key pressed;
    private boolean symbols = false;
    private boolean shift = false;
    private boolean capsLock = false;
    private long lastShiftTap = 0;

    // 组合输入状态
    private final StringBuilder buf = new StringBuilder();
    private final List<String> candidates = new ArrayList<>();
    private final WordStore store;

    private final Runnable deleteRepeat = new Runnable() {
        @Override public void run() {
            handleDelete();
            postDelayed(this, 60);
        }
    };

    public RoundKeyboardView(Context context) { this(context, null); }

    public RoundKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        store = WordStore.get(context);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
        setHapticFeedbackEnabled(true);
    }

    public void setKeyListener(KeyListener l) { listener = l; }

    // ============================== 布局 ==============================

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutKeys(w, h);
    }

    /** 重置状态并重排（切换字母/符号层时调用） */
    private void relayout() {
        shift = false;
        capsLock = false;
        pressed = null;
        buf.setLength(0);
        candidates.clear();
        removeCallbacks(deleteRepeat);
        layoutKeys(getWidth(), getHeight());
        invalidate();
    }

    private void layoutKeys(int w, int h) {
        keys.clear();
        if (w <= 0 || h <= 0) return;

        float size = Math.min(w, h);
        float cx = w / 2f;
        float cy = h / 2f;

        // ---- 边距限制：安全圆半径 = 内切圆半径 - 外边距 ----
        float margin = Math.max(MIN_MARGIN_PX, size * MARGIN_FRACTION);
        float R = size / 2f - margin;

        boolean candMode = !buf.toString().isEmpty() && !candidates.isEmpty();

        if (!symbols) {
            if (candMode) {
                // 顶部候选弧 + 压缩版三排字母
                addCandidateRow(-0.62f, cx, cy, R);
                addArcRow(ROW0, ROW0_Y_WITH_CAND, +0.105f, 0.86f, cx, cy, R);
                addArcRow(ROW1, ROW1_Y_WITH_CAND, +0.030f, 0.90f, cx, cy, R);
                List<String> bottom = new ArrayList<>();
                bottom.add("SHIFT");
                for (String s : ROW2) bottom.add(s);
                bottom.add("DEL");
                addArcRow(bottom.toArray(new String[0]), ROW2_Y_WITH_CAND, -0.105f, 0.86f, cx, cy, R);
            } else {
                addArcRow(ROW0, -0.50f, +0.105f, 0.86f, cx, cy, R);
                addArcRow(ROW1, -0.14f, +0.030f, 0.90f, cx, cy, R);
                List<String> bottom = new ArrayList<>();
                bottom.add("SHIFT");
                for (String s : ROW2) bottom.add(s);
                bottom.add("DEL");
                addArcRow(bottom.toArray(new String[0]), 0.20f, -0.105f, 0.86f, cx, cy, R);
            }

            // 控制键：123 / 空格 / 回车
            addKey("123", null, ACTION_MODE, cx - 0.38f * R, cy + 0.53f * R, 0.090f * R, true);
            addKey("␣", " ", -1, cx, cy + 0.60f * R, 0.115f * R, true);
            addKey("⏎", null, ACTION_ENTER, cx + 0.38f * R, cy + 0.53f * R, 0.090f * R, true);
        } else {
            // 符号层：同几何骨架复用
            addArcRow(SYM0, -0.50f, +0.105f, 0.86f, cx, cy, R);
            addArcRow(SYM1, -0.14f, +0.030f, 0.90f, cx, cy, R);
            List<String> bottom = new ArrayList<>();
            bottom.add("ABC");
            for (String s : SYM2) bottom.add(s);
            bottom.add("DEL");
            addArcRow(bottom.toArray(new String[0]), 0.20f, -0.105f, 0.86f, cx, cy, R);

            addKey("ABC", null, ACTION_MODE, cx - 0.38f * R, cy + 0.53f * R, 0.090f * R, true);
            addKey("␣", " ", -1, cx, cy + 0.60f * R, 0.115f * R, true);
            addKey("⏎", null, ACTION_ENTER, cx + 0.38f * R, cy + 0.53f * R, 0.090f * R, true);
        }

        // ---- 边距限制：任何按键外缘越出安全圆即沿径向拉回 ----
        clampToSafeCircle(cx, cy, R);
    }

    /**
     * 沿圆弧排一行按键。
     *
     * @param y0f    行中心纵向偏移（相对 R，负 = 上方）
     * @param curvef 弧度系数（相对 R，正 = 两端下垂、负 = 两端上翘）
     * @param spanF  行宽占该高度处圆弧弦长的比例（0~1，越小边距越大）
     */
    private void addArcRow(String[] items, float y0f, float curvef, float spanF,
                           float cx, float cy, float R) {
        float y0 = cy + y0f * R;
        float dy = y0f * R;
        float chordHalf = (float) Math.sqrt(Math.max(0f, R * R - dy * dy));
        float halfSpan = chordHalf * spanF;
        int n = items.length;
        if (n == 0 || halfSpan <= 0) return;

        float step = (2f * halfSpan) / n;
        float keyR = Math.min(0.105f * R, step * 0.5f * 0.86f);

        for (int i = 0; i < n; i++) {
            float x = cx - halfSpan + step * (i + 0.5f);
            float t = halfSpan == 0 ? 0 : (x - cx) / halfSpan;
            float y = y0 + curvef * R * t * t;

            String s = items[i];
            if ("SHIFT".equals(s)) {
                addKey("⇧", null, ACTION_SHIFT, x, y, keyR, true);
            } else if ("DEL".equals(s)) {
                addKey("⌫", null, ACTION_DELETE, x, y, keyR, true);
            } else if ("ABC".equals(s) || "123".equals(s)) {
                addKey(s, null, ACTION_MODE, x, y, keyR, true);
            } else {
                // 字母/符号：上屏用小写，显示用原样
                addKey(s, s.toLowerCase(), -1, x, y, keyR, false);
            }
        }
    }

    /** 候选词沿顶部弧横向均匀排布（1~3 个） */
    private void addCandidateRow(float y0f, float cx, float cy, float R) {
        int n = candidates.size();
        if (n == 0) return;
        float y = cy + y0f * R;
        float r = 0.088f * R;
        float spacing = 0.30f * R;
        for (int i = 0; i < n; i++) {
            float x = cx + (i - (n - 1) / 2f) * spacing;
            Key k = new Key();
            k.label = candidates.get(i);
            k.text = candidates.get(i);
            k.candIndex = i;
            k.x = x;
            k.y = y;
            k.r = r;
            k.control = true;
            keys.add(k);
        }
    }

    private void addKey(String label, String text, int action,
                        float x, float y, float r, boolean control) {
        Key k = new Key();
        k.label = label;
        k.text = text;
        k.action = action;
        k.x = x;
        k.y = y;
        k.r = r;
        k.control = control;
        keys.add(k);
    }

    /** 边距限制的核心：按键外缘越出「安全圆」就沿径向收回 */
    private void clampToSafeCircle(float cx, float cy, float R) {
        float maxD = R * SAFE_FACTOR;
        for (Key k : keys) {
            float dx = k.x - cx, dy = k.y - cy;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > 0 && d + k.r > maxD) {
                float f = (maxD - k.r) / d;
                k.x = cx + dx * f;
                k.y = cy + dy * f;
            }
        }
    }

    // ============================== 组合输入 ==============================

    private void updateCandidates() {
        candidates.clear();
        String p = buf.toString();
        if (!p.isEmpty() && store.hasDictionary()) {
            candidates.addAll(store.prefix(p, 3));
        }
        layoutKeys(getWidth(), getHeight());
        invalidate();
    }

    // ============================== 绘制 ==============================

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Key k : keys) {
            int fill;
            if (k == pressed) {
                fill = 0xFF5A5A66;
            } else if (k.candIndex == 0) {
                fill = 0xFF3D5A8A;                    // 首选候选
            } else if (k.candIndex > 0) {
                fill = 0xFF2E3D5C;                    // 其他候选
            } else if (k.action == ACTION_SHIFT && (shift || capsLock)) {
                fill = 0xFF5B8CFF;                    // shift 激活高亮
            } else if (k.action == ACTION_MODE && symbols) {
                fill = 0xFF5B8CFF;                    // 符号层激活高亮
            } else if (k.control) {
                fill = 0xFF3A3A44;
            } else {
                fill = 0xFF2B2B33;
            }
            keyPaint.setColor(fill);
            canvas.drawCircle(k.x, k.y, k.r, keyPaint);

            String label = k.label;
            if (k.action == ACTION_SHIFT && capsLock) label = "⇪";
            if (k.text != null && " ".equals(k.text) && buf.length() > 0) {
                label = buf.toString();               // 组合串显示在空格键上
            }
            if (k.text != null && !k.control && k.candIndex < 0 && shift && !symbols && !capsLock
                    && k.text.length() == 1 && Character.isLowerCase(k.text.charAt(0))) {
                label = label.toUpperCase();
            }

            float size = k.r * (k.control ? 0.85f : 0.95f);
            if (label.length() > 2) {                 // 候选词过长时缩小字号
                float fit = (k.r * 1.7f) / (0.62f * label.length());
                size = Math.min(size, fit);
            }
            textPaint.setTextSize(size);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float ty = k.y - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(label, k.x, ty, textPaint);
        }
    }

    // ============================== 触控 ==============================

    @Override public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                updatePressed(x, y);
                if (pressed != null && pressed.action == ACTION_DELETE) {
                    postDelayed(deleteRepeat, 400); // 长按连续删除
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pressed != null) updatePressed(x, y);
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(deleteRepeat);
                if (pressed != null) {
                    fireKey(pressed);
                    pressed = null;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(deleteRepeat);
                pressed = null;
                invalidate();
                return true;
        }
        return super.onTouchEvent(ev);
    }

    /** 圆屏命中：取「按键外缘外距离最小」的键，允许手指容差 */
    private void updatePressed(float x, float y) {
        Key best = null;
        float bestD = Float.MAX_VALUE;
        for (Key k : keys) {
            float dx = x - k.x, dy = y - k.y;
            float d = (float) Math.sqrt(dx * dx + dy * dy) - k.r;
            if (d < 0) d = 0;
            if (d < bestD && d < k.r * (TOUCH_SLOP - 1f)) {
                bestD = d;
                best = k;
            }
        }
        if (best != pressed) {
            if (best == null) removeCallbacks(deleteRepeat);
            pressed = best;
            if (pressed != null) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
            invalidate();
        }
    }

    private void fireKey(Key k) {
        // 候选词上屏
        if (k.candIndex >= 0) {
            if (k.text != null && listener != null) listener.onText(k.text);
            resetComposition();
            return;
        }

        if (k.action >= 0) {
            if (k.action == ACTION_SHIFT) {
                long now = System.currentTimeMillis();
                if (now - lastShiftTap < 350) {
                    capsLock = !capsLock;      // 双击锁定大写
                } else {
                    shift = !shift;
                    capsLock = false;
                }
                lastShiftTap = now;
                invalidate();
                return;
            }
            if (k.action == ACTION_MODE) {
                symbols = !symbols;
                relayout();
                return;
            }
            if (k.action == ACTION_DELETE) {
                handleDelete();
                return;
            }
            if (k.action == ACTION_ENTER) {
                // 回车：先上屏未提交的组合串（若有），再换行
                if (buf.length() > 0 && listener != null) {
                    listener.onText(buf.toString());
                    resetComposition();
                }
                if (listener != null) listener.onAction(ACTION_ENTER);
                return;
            }
            return;
        }

        if (k.text != null && listener != null) {
            if (" ".equals(k.text)) {
                handleSpace();                // 空格：上屏候选/组合串 + 空格
            } else if (!symbols && store.hasDictionary()) {
                // 有词库：字母进入组合串
                buf.append(k.text);
                updateCandidates();
            } else {
                // 无词库：字母直通上屏
                String t = k.text;
                if (!symbols && shift && t.length() == 1 && Character.isLowerCase(t.charAt(0))) {
                    t = t.toUpperCase();
                }
                listener.onText(t);
                if (shift && !capsLock) {
                    shift = false;
                    invalidate();
                }
            }
        }
    }

    /** 空格：有组合串时上屏首选候选（或原始串），否则上屏空格 */
    private void handleSpace() {
        if (buf.length() > 0 && listener != null) {
            String t = candidates.isEmpty() ? buf.toString() : candidates.get(0);
            listener.onText(t);
            resetComposition();
        }
        if (listener != null) listener.onText(" ");
    }

    private void handleDelete() {
        if (buf.length() > 0) {
            buf.deleteCharAt(buf.length() - 1);
            updateCandidates();
        } else if (listener != null) {
            listener.onAction(ACTION_DELETE);
        }
    }

    private void resetComposition() {
        buf.setLength(0);
        candidates.clear();
        layoutKeys(getWidth(), getHeight());
        invalidate();
    }
}
