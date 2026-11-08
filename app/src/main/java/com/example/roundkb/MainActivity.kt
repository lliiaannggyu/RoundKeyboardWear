package com.example.roundkb

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import java.io.InputStream

/**
 * 安装引导 + 词库与全局设置页 —— Wear Compose **Material 3** 实现。
 *
 * UI 结构（圆屏自适应，可滚动）：
 *  - 标题 + 词库状态
 *  - 「图标大小」小 / 标准 / 大（全局功能键图标缩放，键盘即时生效）
 *  - 「按键震动」关 / 弱 / 中 / 强（自定义时长与振幅）
 *  - 「去开启输入法」「导入词库」「清空词库」
 *
 * 键盘本体（RoundKeyboardView）保持自绘 View：输入法需要极低延迟与
 * 完全受控的圆屏触控，这也是 Gboard 等主流输入法的做法。
 */
class MainActivity : ComponentActivity() {

    private val stats = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stats.value = WordStore.get(this).info()
        setContent {
            MaterialTheme {
                RoundKbScreen(stats) { refreshStats() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    private fun refreshStats() {
        stats.value = WordStore.get(this).info()
    }
}

@Composable
private fun RoundKbScreen(stats: MutableState<String>, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    var iconScale by remember { mutableStateOf(SettingsStore.iconScale(context)) }
    var haptic by remember { mutableStateOf(SettingsStore.hapticMode(context)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && activity != null) {
            stats.value = importDict(activity, uri)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "圆屏 26 键",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stats.value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 2.dp)
            )

            // ---- 图标大小 ----
            SettingLabel("图标大小")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ChoiceChip("小", iconScale == 0.80f) {
                    SettingsStore.setIconScale(context, 0.80f); iconScale = 0.80f
                }
                ChoiceChip("标准", iconScale == 1.0f) {
                    SettingsStore.setIconScale(context, 1.0f); iconScale = 1.0f
                }
                ChoiceChip("大", iconScale == 1.25f) {
                    SettingsStore.setIconScale(context, 1.25f); iconScale = 1.25f
                }
            }

            // ---- 按键震动 ----
            SettingLabel("按键震动")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ChoiceChip("关", haptic == SettingsStore.HAPTIC_OFF) {
                    SettingsStore.setHapticMode(context, SettingsStore.HAPTIC_OFF); haptic = SettingsStore.HAPTIC_OFF
                }
                ChoiceChip("弱", haptic == SettingsStore.HAPTIC_WEAK) {
                    SettingsStore.setHapticMode(context, SettingsStore.HAPTIC_WEAK); haptic = SettingsStore.HAPTIC_WEAK
                }
                ChoiceChip("中", haptic == SettingsStore.HAPTIC_MEDIUM) {
                    SettingsStore.setHapticMode(context, SettingsStore.HAPTIC_MEDIUM); haptic = SettingsStore.HAPTIC_MEDIUM
                }
                ChoiceChip("强", haptic == SettingsStore.HAPTIC_STRONG) {
                    SettingsStore.setHapticMode(context, SettingsStore.HAPTIC_STRONG); haptic = SettingsStore.HAPTIC_STRONG
                }
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

            Text(
                text = "滑过字母=整词输入 · 点按=逐字母\n词库为 UTF-8 文本，导入即生效",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium
    )
}

/** M3 筛选芯片：设置项单选 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label, fontSize = 11.sp) },
        colors = FilterChipDefaults.filterChipColors()
    )
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
