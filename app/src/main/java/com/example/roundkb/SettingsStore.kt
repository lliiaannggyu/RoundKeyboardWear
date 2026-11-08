package com.example.roundkb

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * 全局设置：图标缩放 / 按键震动。
 * SharedPreferences 持久化 + 内存缓存（键盘绘制/震动高频读取，避免每次读盘）。
 */
object SettingsStore {
    private const val PREFS = "settings"
    private const val KEY_ICON = "icon_scale"
    private const val KEY_HAPTIC = "haptic_mode"
    private const val KEY_KEY_SCALE = "key_scale"

    /** 震动档位 */
    const val HAPTIC_OFF = 0
    const val HAPTIC_WEAK = 1
    const val HAPTIC_MEDIUM = 2
    const val HAPTIC_STRONG = 3

    private var iconScaleCache = Float.NaN
    private var hapticCache = -1
    private var keyScaleCache = Float.NaN

    /** 按键大小全局缩放（字母键/功能键整体，1.0 = 标准） */
    fun keyScale(ctx: Context): Float {
        if (!keyScaleCache.isNaN()) return keyScaleCache
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_KEY_SCALE, 1.0f)
        keyScaleCache = v
        return v
    }

    fun setKeyScale(ctx: Context, value: Float) {
        keyScaleCache = value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_KEY_SCALE, value).apply()
    }

    /** 功能键图标全局缩放（1.0 = 标准） */
    fun iconScale(ctx: Context): Float {
        if (!iconScaleCache.isNaN()) return iconScaleCache
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_ICON, 1.0f)
        iconScaleCache = v
        return v
    }

    fun setIconScale(ctx: Context, value: Float) {
        iconScaleCache = value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_ICON, value).apply()
    }

    fun hapticMode(ctx: Context): Int {
        if (hapticCache >= 0) return hapticCache
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_HAPTIC, HAPTIC_MEDIUM)
        hapticCache = v
        return v
    }

    fun setHapticMode(ctx: Context, mode: Int) {
        hapticCache = mode
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_HAPTIC, mode).apply()
    }

    /**
     * 按当前档位执行一次按键震动。
     * @param strong 强提示（滑行解码无命中等），固定用最强档
     */
    fun keyVibrate(ctx: Context, strong: Boolean = false) {
        val mode = if (strong) HAPTIC_STRONG else hapticMode(ctx)
        if (mode == HAPTIC_OFF) return
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        val ms: Long
        val amp: Int
        when (mode) {
            HAPTIC_WEAK -> { ms = 12L; amp = 64 }
            HAPTIC_STRONG -> { ms = 28L; amp = 255 }
            else -> { ms = 18L; amp = 140 }   // HAPTIC_MEDIUM
        }
        v.vibrate(VibrationEffect.createOneShot(ms, amp))
    }
}
