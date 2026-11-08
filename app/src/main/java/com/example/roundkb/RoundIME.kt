package com.example.roundkb

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import kotlin.math.min

/**
 * 圆屏 26 键输入法服务（Kotlin）。
 */
class RoundIME : InputMethodService(), RoundKeyboardView.KeyListener {

    private var kb: RoundKeyboardView? = null

    override fun onCreateInputView(): View {
        kb = RoundKeyboardView(this).apply {
            keyListener = this@RoundIME
            // 使用正方形区域：圆屏下 min(宽,高) 即直径，保证键盘是完整的圆
            val dm = resources.displayMetrics
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                min(dm.widthPixels, dm.heightPixels)
            )
        }
        return kb!!
    }

    /** 关闭全屏抽取模式，避免长文本输入时键盘被系统放大变形 */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // 应用内更新词库 / 全局设置（按键大小等）后自动生效
        WordStore.get(this).reloadIfChanged()
        kb?.refreshLayout()
    }

    override fun onText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun onAction(action: Int) {
        val ic = currentInputConnection ?: return
        when (action) {
            RoundKeyboardView.ACTION_DELETE -> {
                if (!ic.deleteSurroundingText(1, 0)) {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                }
            }
            RoundKeyboardView.ACTION_ENTER -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            RoundKeyboardView.ACTION_IME_PICKER -> {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showInputMethodPicker()
            }
            RoundKeyboardView.ACTION_HIDE -> requestHideSelf(0)
        }
    }
}
