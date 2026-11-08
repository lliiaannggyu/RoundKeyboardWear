# 圆屏 26 键输入法（Wear OS / Kotlin）

为 **Wear OS 圆形表盘** 专门设计的 26 键 QWERTY 输入法（Kotlin）。
参考 **Gboard for Wear OS** 的设计语言：圆角矩形键帽沿表盘**同心圆弧**排布、
宽胶囊空格、Gboard 蓝功能键，并带有**双重边距限制**，任何分辨率圆屏下都不贴边、不被表盘裁切。

![预览](preview.png)

## 特性

| 特性 | 说明 |
|---|---|
| Gboard 直排布局 | 三排按键**水平直线**（Gboard for Wear OS 式），行宽按「该行高度处圆弧弦长 × 0.88」截取，两端自然收进圆内；底部**宽胶囊空格** + 123 / ⏎（Gboard 蓝）；候选词顶部横排、首选高亮 |
| 边距限制 ① | 键盘整体留出 `max(10px, 5%×直径)` 的外边距 |
| 边距限制 ② | 任何按键外缘越出「安全圆」（96.5% 内切圆）都会被**自动沿径向拉回** |
| 圆屏触控 | 圆角矩形最近命中 + 手指容差；长按 ⌫ 连续删除；⇧ 单击大写 / 双击锁定（⇪）；123 ⇄ ABC 符号层 |
| **Gboard 等宽布局** | 三行**等宽键**（键宽统一按最宽行 10 键 × 顶部行弦长确定），行居中、行宽随键数自然递减（10→9→9），符合 QWERTY 视觉习惯 |
| **M3 设置页** | 应用内设置页用 **Material 3 (Compose)** 组件库重构：M3 按钮与配色、词库状态、SAF 文件导入；键盘本体保持自绘 View（IME 低延迟标准做法） |
| **滑动输入** | **SHARK² 统计解码**（移植自 FlorisBoard 的 `StatisticalGlideTypingClassifier`，Apache-2.0）：端点剪枝（起/终点各最近 2 键）→ 长度剪枝 → **形状高斯**（按包围盒归一化，与表盘尺寸无关）× **位置高斯** × **词频** 综合置信度排序；重复字母自动生成绕环理想路径区分 pool/poll |
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
滑动解码分四步（`GlideDecoder.kt`）：① 采样抽稀 ② 端点剪枝：手势起/终点各取最近 2 键，命中 (首键,尾键) 词树（索引在 `WordStore` 预建）③ 长度剪枝：理想路径长度与手势长度差 > 14×键半径 的词丢弃（为圆屏小键与拼音长词放宽，剪空则回退）④ 打分：形状距离（归一化）/位置距离（L1）套高斯概率，乘对数归一化词频，confidence 越小越优。

## 目录结构

```
app/src/main/java/com/example/roundkb/
├── RoundKeyboardView.kt   # 核心：Gboard 风格圆弧布局 + 边距限制 + 纠错 + 滑行手势 + 绘制
├── WordStore.kt           # 自定义词库：解析 / 二分前缀匹配 / 手势词过滤 / 应用内导入更新
├── RoundIME.kt            # InputMethodService，接 InputConnection
└── MainActivity.kt        # 启用引导 + 词库导入 / 清空 / 统计
```

## 致谢

- [FlorisBoard](https://github.com/florisboard/florisboard)（Apache-2.0）—— `GlideDecoder.kt` 移植自其 `StatisticalGlideTypingClassifier`
- [AnySoftKeyboard PR #1870](https://github.com/AnySoftKeyboard/AnySoftKeyboard/pull/1870)（Étienne Desticourt）—— 上述实现的源头
- SHARK²：Kristensson & Zhai 的 shapewriter-on-keyboard 算法

## 更新日志

- **v1.5**：布局改为 **Gboard 等宽键**（行宽随键数 10→9→9 递减，修复第一行反而最短的问题）；设置页引入 **Material 3 (Compose)** 组件库重构；APK 增大至 ~7.6MB（Compose 运行时）

- **v1.4**：滑动输入整体替换为 **SHARK² 统计解码**（移植 FlorisBoard，端点/长度剪枝 + 形状/位置高斯 + 词频），离线模拟命中率 top1 93%；新增 `GlideDecoder.kt`，词库增加端点索引

- **v1.3**：行排布改为 **Gboard 式水平直排**（不再弧形弯曲）；滑动输入算法升级（子序列校验 + 双向 Chamfer + 字母必经性 + 词频加成），轨迹字母不再被直接当词用
- **v1.2**：全面改用 **Kotlin**；Gboard 风格重构（圆角键帽 / 宽胶囊空格 / 蓝色功能键 / 顶部候选弧）；新增**滑动手势输入**与**1 次编辑纠错**
- **v1.1**：自定义词库（应用内导入/清空/统计，导入即生效）、组合输入
- **v1.0**：圆弧排布 26 键、双重边距限制、Shift/大写锁定、符号层、长按退格
