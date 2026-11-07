package com.example.roundkb;

import android.inputmethodservice.InputMethodService;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * 圆屏 26 键输入法服务。
 */
public class RoundIME extends InputMethodService implements RoundKeyboardView.KeyListener {

    private RoundKeyboardView kb;

    @Override public View onCreateInputView() {
        kb = new RoundKeyboardView(this);
        kb.setKeyListener(this);

        // 使用正方形区域：圆屏下 min(宽,高) 即直径，保证键盘是完整的圆
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        kb.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        return kb;
    }

    /** 关闭全屏抽取模式，避免长文本输入时键盘被系统放大变形 */
    @Override public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        // 应用内更新词库后自动生效
        WordStore.get(this).reloadIfChanged();
        if (kb != null) kb.invalidate();
    }

    @Override public void onText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }

    @Override public void onAction(int action) {
        switch (action) {
            case RoundKeyboardView.ACTION_DELETE: {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    if (!ic.deleteSurroundingText(1, 0)) {
                        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
                    }
                }
                break;
            }
            case RoundKeyboardView.ACTION_ENTER: {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                }
                break;
            }
        }
    }
}
