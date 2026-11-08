# 圆屏 26 键输入法（Wear OS / Kotlin）

为 **Wear OS 圆形表盘** 专门设计的 26 键 QWERTY 输入法（Kotlin）。
参考 **Gboard for Wear OS** 的设计语言：圆角矩形键帽沿表盘**同心圆弧**排布、
宽胶囊空格、Gboard 蓝功能键，并带有**双重边距限制**，任何分辨率圆屏下都不贴边、不被表盘裁切。

![预览](preview.png)

## 特性

| 特性 | 说明 |
|---|---|
| Gboard 风格布局 | 圆角矩形键帽三排沿同心圆弧分布；底部**宽胶囊空格** + 123 / ⏎（Gboard 蓝）；候选词沿顶部弧排布、首选高亮 |
| 边距限制 ① | 键盘整体留出 `max(10px, 5%×直径)` 的外边距 |
| 边距限制 ② | 任何按键外缘越出「安全圆」（96.5% 内切圆）都会被**自动沿径向拉回** |
| 圆屏触控 | 圆角矩形最近命中 + 手指容差；长按 ⌫ 连续删除；⇧ 单击大写 / 双击锁定（⇪）；123 ⇄ ABC 符号层 |
| **滑动输入** | 在字母间连续滑动即可整词输入：轨迹等弧长重采样 → 与「首尾字母一致」的词做路径贴合打分（含长度差惩罚）→ 取最优 3 个候选 |
| **智能纠错** | 漏按/错按自动修正：词频优先的前缀匹配 + **1 次编辑距离**（删除/替换/插入）纠错；滑行未命中时自动退化为组合纠错 |
| 自定义词库 | 应用内导入任意 UTF-8 词库文本（英文联想 / 拼音→中文），**导入即生效（应用内更新，无需重装）** |
| 低依赖 | Kotlin + Android 框架 API，无 AndroidX、无其他第三方库 |

## 国内镜像配置（已内置）

| 位置 | 镜像 |
|---|---|
| `settings.gradle` | 阿里云 `maven.aliyun.com`（google / gradle-plugin / public），官方源兜底 |
| `gradle/wrapper/gradle-wrapper.properties` | 腾讯云 `mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip` |

## 构建打包

```bash
# 方式一：Android Studio 直接打开工程，Build > Build APK(s)

# 方式二：命令行（需 JDK 17+，SDK 34 + Build-Tools 34.0.0）
./gradlew assembleDebug          # 或 gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 安装启用（手表上）

1. `adb install -r app-debug.apk`（或从 `apk/` 目录取现成包）
2. 打开手表上的「圆屏26键输入法」应用，点「去开启输入法」勾选它
3. 在任意输入框长按 → 切换输入法 → 选择「圆屏26键」

## 输入技巧

- **滑动输入**：按住第一个字母 → 连续滑过单词路径 → 抬起，自动匹配整词（需已导入词库）；候选未命中会自动退化为组合纠错
- **点按输入**：字母进入组合串（显示在空格键上），顶部弧出现最多 3 个候选；漏按/错按自动按 1 次编辑距离修正
- **空格** = 上屏首选候选 + 空格；**回车** = 上屏原始字母串；**退格**优先删组合串
- 未导入词库时字母直通上屏（Shift 转大写）

## 调参指南

所有几何参数集中在 `RoundKeyboardView.kt` 顶部 `companion object`：

| 常量 | 含义 | 默认 |
|---|---|---|
| `MARGIN_FRACTION` | 外边距占直径比例 | 0.05 |
| `SAFE_FACTOR` | 安全圆占内切圆比例（越界拉回） | 0.965 |
| `HIT_SLOP_MUL / HIT_SLOP_ADD` | 触点容错 | 1.15 / 8px |
| `GESTURE_START / GESTURE_MIN_PATH` | 滑行触发位移 / 最短轨迹 | 0.30R / 0.85R |
| `addArcRow(...)` 的 `y0f/curvef/spanF` | 各行纵向位置 / 弧度 / 行宽占比 | 见源码注释 |

## 自定义词库（应用内更新）

打开手表上的「圆屏26键输入法」App → **导入词库**，选择 UTF-8 文本文件（`.txt`）即可，**导入立即生效，随时可重新导入更新，无需重装应用**。

文件格式（每行一条，可参考仓库内 `sample_dict.txt`）：

| 写法 | 示例 | 效果 |
|---|---|---|
| 单词一行 | `hello` | 输入 `hel` / 滑动联想 hello |
| 键 值（空格分隔） | `nihao 你好` | 输入/滑行 `nihao` 候选「你好」 |
| 键 TAB 值 TAB 词频 | `nihao<TAB>你好<TAB>500` | 词频越大候选越靠前 |
| 注释 | `# 注释` | 忽略 |

### 词库工作原理

`WordStore` 把词库解析为 `(key, value, freq)`，按 key 排序后二分查找做前缀匹配（单次扫描上限 500 条，总词条上限 20 万）；
滑动输入先按「首字母 + 尾字母」过滤（上限 4000 词），再对每词构造理想路径与轨迹做贴合度打分。

## 目录结构

```
app/src/main/java/com/example/roundkb/
├── RoundKeyboardView.kt   # 核心：Gboard 风格圆弧布局 + 边距限制 + 纠错 + 滑行手势 + 绘制
├── WordStore.kt           # 自定义词库：解析 / 二分前缀匹配 / 手势词过滤 / 应用内导入更新
├── RoundIME.kt            # InputMethodService，接 InputConnection
└── MainActivity.kt        # 启用引导 + 词库导入 / 清空 / 统计
```

## 更新日志

- **v1.2**：全面改用 **Kotlin**；Gboard 风格重构（圆角键帽 / 宽胶囊空格 / 蓝色功能键 / 顶部候选弧）；新增**滑动手势输入**与**1 次编辑纠错**
- **v1.1**：自定义词库（应用内导入/清空/统计，导入即生效）、组合输入
- **v1.0**：圆弧排布 26 键、双重边距限制、Shift/大写锁定、符号层、长按退格
