package com.example.roundkb

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * 全局设置（连续数值，滑块调节）：
 *  - 按键大小 keyScale（0.8~1.2，字母/功能/候选键整体缩放）
 *  - 图标大小 iconScale（0.6~1.6，功能键图标缩放）
 *  - 按键震动 hapticAmp（0~255，0=关）
 * SharedPreferences 持久化 + 内存缓存（键盘绘制/震动高频读取）。
 */
object SettingsStore {
    private const val PREFS = "settings"
    private const val KEY_ICON = "icon_scale"
    private const val KEY_KEY_SCALE = "key_scale"
    private const val KEY_HAPTIC = "haptic_amp"
    private const val KEY_FUZZY = "fuzzy_pinyin"

    const val KEY_SCALE_MIN = 0.8f
    const val KEY_SCALE_MAX = 1.2f
    const val ICON_SCALE_MIN = 0.6f
    const val ICON_SCALE_MAX = 1.6f
    const val HAPTIC_MAX = 255f

    private var iconScaleCache = Float.NaN
    private var keyScaleCache = Float.NaN
    private var hapticCache = Float.NaN

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

    /** 震动振幅 0~255，0 = 关闭 */
    fun hapticAmp(ctx: Context): Float {
        if (!hapticCache.isNaN()) return hapticCache
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_HAPTIC, 140f)
        hapticCache = v
        return v
    }

    fun setHapticAmp(ctx: Context, value: Float) {
        hapticCache = value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_HAPTIC, value).apply()
    }

    /** 模糊音（zh/z ch/c sh/s、an/ang en/eng in/ing ian/iang uan/uang），默认开 */
    fun fuzzy(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FUZZY, true)

    fun setFuzzy(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FUZZY, value).apply()
    }

    /**
     * 按当前振幅执行一次按键震动（振幅越大震动越强、略久）。
     * @param strong 强提示（滑行无命中等），固定最强档
     */
    fun keyVibrate(ctx: Context, strong: Boolean = false) {
        val ampF = if (strong) HAPTIC_MAX else hapticAmp(ctx)
        if (ampF <= 0f) return
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        val amp = ampF.toInt().coerceIn(1, 255)
        val ms = (12L + (ampF / HAPTIC_MAX * 22f)).toLong()
        v.vibrate(VibrationEffect.createOneShot(ms, amp))
    }
}
