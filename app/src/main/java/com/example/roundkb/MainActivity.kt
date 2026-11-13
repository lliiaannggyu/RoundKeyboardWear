package com.example.roundkb

import android.Manifest
import androidx.core.app.ActivityCompat
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 安装引导 + 词库与全局设置页 —— Wear Compose **Material 3** 实现。
 *
 * UI 结构（圆屏自适应，可滚动）：
 *  - 标题 + 词库状态
 *  - **实时预览条**：Q W E + 退格图标，随「按键大小」「图标大小」滑块即时缩放
 *  - 「按键大小」「图标大小」「按键震动」三个连续滑块，旁边显示当前数值
 *    （震动滑块松手试震一次）
 *  - 「去开启输入法」「导入词库」「清空词库」
 */
class MainActivity : ComponentActivity() {

    private val stats = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 麦克风权限：键盘工具条 🎤 语音输入用（只在首次启动时弹一次授权）
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        stats.value = WordStore.get(this).info()
        // 提前启动 librime（设置页打开时后台部署，下次呼出键盘即就绪）
        RimeEngine.ensureStarted(this)
        RimeEngine.addReadyListener { refreshStats() }
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        setContent {
            MaterialTheme {
                RoundKbScreen(stats, ver) { refreshStats() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    private fun refreshStats() {
        val eng = when {
            RimeEngine.ready -> "Rime 拼音引擎：就绪"
            RimeEngine.deploying -> "Rime 拼音引擎：词库部署中…"
            else -> "Rime 拼音引擎：启动中…"
        }
        stats.value = "$eng\n" + WordStore.get(this).info()
    }
}

@Composable
private fun RoundKbScreen(stats: MutableState<String>, ver: String, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    var keyScale by remember { mutableStateOf(SettingsStore.keyScale(context)) }
    var iconScale by remember { mutableStateOf(SettingsStore.iconScale(context)) }
    var hapticAmp by remember { mutableStateOf(SettingsStore.hapticAmp(context)) }
    var layout by remember { mutableStateOf(SettingsStore.keyLayout(context)) }
    var updMsg by remember { mutableStateOf("") }
    var updBtn by remember { mutableStateOf(UpdateChecker.buttonLabel()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && activity != null) {
            stats.value = importDict(activity, uri)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            Text(
                text = if (ver.isEmpty()) "圆屏 26 键" else "圆屏 26 键  v$ver",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stats.value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center
            )

            // ---- 实时预览：随滑块即时缩放 ----
            KeyboardPreview(keyScale, iconScale)

            // ---- 按键大小 ----
            SliderRow(
                label = "按键大小",
                valueText = String.format(Locale.US, "%.2f×", keyScale),
                value = keyScale,
                range = SettingsStore.KEY_SCALE_MIN..SettingsStore.KEY_SCALE_MAX,
                onChange = {
                    keyScale = it
                    SettingsStore.setKeyScale(context, it)
                }
            )

            // ---- 图标大小 ----
            SliderRow(
                label = "图标大小",
                valueText = String.format(Locale.US, "%.2f×", iconScale),
                value = iconScale,
                range = SettingsStore.ICON_SCALE_MIN..SettingsStore.ICON_SCALE_MAX,
                onChange = {
                    iconScale = it
                    SettingsStore.setIconScale(context, it)
                }
            )

            // ---- 按键震动（0 = 关；松手试震一次） ----
            SliderRow(
                label = "按键震动",
                valueText = if (hapticAmp <= 0f) "关"
                    else "${(hapticAmp / SettingsStore.HAPTIC_MAX * 100).roundToInt()}%",
                value = hapticAmp,
                range = 0f..SettingsStore.HAPTIC_MAX,
                onChange = {
                    hapticAmp = it
                    SettingsStore.setHapticAmp(context, it)
                },
                onFinished = { SettingsStore.keyVibrate(context) }   // 松手试震
            )

            // ---- 键盘布局：26 键 / 九键 / 双键（下次呼出键盘生效） ----
            M3Button("键盘布局：${SettingsStore.LAYOUT_NAMES[layout]}", R.drawable.ic_settings) {
                layout = (layout + 1) % 3
                SettingsStore.setKeyLayout(context, layout)
            }

            // ---- 模糊音开关（切换后词库索引后台重建） ----
            var fuzzyOn by remember { mutableStateOf(SettingsStore.fuzzy(context)) }
            M3Button(if (fuzzyOn) "模糊音：开" else "模糊音：关", R.drawable.ic_settings) {
                fuzzyOn = !fuzzyOn
                SettingsStore.setFuzzy(context, fuzzyOn)
                Thread {
                    WordStore.get(context).reloadFuzzy()
                    activity?.runOnUiThread { onRefresh() }
                }.start()
            }
            M3Button("去开启输入法", R.drawable.ic_settings) {
                activity?.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            M3Button("导入词库", R.drawable.ic_upload) {
                picker.launch(arrayOf(
                    "text/plain", "text/tab-separated-values", "text/csv", "application/octet-stream"))
            }
            M3Button("清空词库", R.drawable.ic_delete) {
                WordStore.get(context).clear()
                onRefresh()
            }

            // ---- 应用内更新：检查 → 下载 → 拉起系统安装器 ----
            if (updMsg.isNotEmpty()) {
                Text(
                    text = updMsg,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
            M3Button(updBtn, R.drawable.ic_upload) {
                if (activity == null) return@M3Button
                UpdateChecker.tap(activity) { msg ->
                    updMsg = msg
                    updBtn = UpdateChecker.buttonLabel()
                }
                updBtn = UpdateChecker.buttonLabel()
            }

            Text(
                text = "整句拼音：直接连打 · 候选行可翻页\n九键：数字进 T9 组合，1 键上屏首选\n双键：点键的左右半区选字母\n空格上横向滑动 = 移动光标\n长按 ?123 = 英文直输 · 滑过字母=整词输入",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 迷你键盘预览条：三个字母键 + 一个退格功能键，实时反映按键/图标缩放 */
@Composable
private fun KeyboardPreview(keyScale: Float, iconScale: Float) {
    val keyW = 30 * keyScale
    val keyH = 26 * keyScale
    val corner = (7 * keyScale).dp
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        listOf("Q", "W", "E").forEach { ch ->
            Box(
                modifier = Modifier
                    .width(keyW.dp).height(keyH.dp)
                    .background(Color(0xFF313338), RoundedCornerShape(corner)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ch,
                    color = Color.White,
                    fontSize = (13 * keyScale).sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Box(
            modifier = Modifier
                .width(keyW.dp).height(keyH.dp)
                .background(Color(0xFF3C4043), RoundedCornerShape(corner)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_backspace),
                contentDescription = null,
                modifier = Modifier.size((11 * keyScale * iconScale).dp),
                tint = Color.White
            )
        }
    }
}

/** 标签 + 当前数值 + 连续滑块 */
@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth(0.94f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = valueText,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            ),
            modifier = Modifier.fillMaxWidth().height(22.dp)
        )
    }
}

/** 导入词库文件，返回结果信息（成功=词库状态，失败=错误描述） */
private fun importDict(activity: ComponentActivity, uri: Uri): String {
    return try {
        val input: InputStream? = activity.contentResolver.openInputStream(uri)
        if (input == null) {
            "导入失败：无法打开文件"
        } else {
            WordStore.get(activity).importFrom(input, queryName(activity, uri))
            WordStore.get(activity).info()
        }
    } catch (e: Exception) {
        "导入失败：${e.message}"
    }
}

private fun queryName(activity: ComponentActivity, uri: Uri): String {
    var c: Cursor? = null
    return try {
        c = activity.contentResolver.query(uri, null, null, null, null)
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

/** M3 按钮封装：统一宽度、Material 图标与文字样式 */
@Composable
private fun M3Button(label: String, iconRes: Int, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(0.72f).padding(vertical = 1.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onPrimary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
