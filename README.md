# 圆屏 26 键输入法（Wear OS）

为 **Wear OS 圆形表盘** 专门设计的 26 键 QWERTY 输入法。不是「方屏里画圆」，
而是按键沿表盘**同心圆弧**排布，并带有**双重边距限制**，保证任何分辨率圆屏下都不贴边、不被表盘裁切。

![预览](preview.png)

## 特性

| 特性 | 说明 |
|---|---|
| 圆弧行排布 | 三排按键各自沿同心圆弧分布，两端自然收进圆内（Q~P 下垂弧、A~L 近直弧、⇧Z~M⌫ 上翘弧） |
| 边距限制 ① | 键盘整体留出 `max(10px, 5.5%×直径)` 的外边距（`MARGIN_FRACTION`） |
| 边距限制 ② | 任何按键外缘越出「安全圆」（96% 内切圆）都会被**自动沿径向拉回**（`clampToSafeCircle`） |
| 圆屏触控 | 按「最近键命中 + 手指容差」判定，支持滑选；长按 ⌫ 连续删除 |
| 状态键 | ⇧ 单击临时大写 / 双击锁定大写（⇪）；123 ⇄ ABC 符号层；空格 / ⏎ |
| 自定义词库 | 应用内导入任意 UTF-8 词库文本（英文联想 / 拼音→中文），候选词沿表盘顶部弧排布，**导入即生效（应用内更新，无需重装）** |
| 零依赖 | 纯 Android 框架 API（Java），无 AndroidX、无第三方库，打包最快 |

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

Release 签名包：`gradle assembleRelease`（自行配置签名或用 debug 签名调试）。

## 安装启用（手表上）

1. `adb install -r app-debug.apk`
2. 打开手表上的「圆屏26键输入法」应用，点「去开启输入法」勾选它
   （或：设置 → 输入法 → 勾选「圆屏26键」）
3. 在任意输入框长按 → 切换输入法 → 选择「圆屏26键」

## 调参指南

所有几何参数集中在 `RoundKeyboardView.java` 顶部常量区：

| 常量 | 含义 | 默认 |
|---|---|---|
| `MARGIN_FRACTION` | 外边距占直径比例 | 0.055 |
| `MIN_MARGIN_PX` | 边距下限 | 10px |
| `SAFE_FACTOR` | 安全圆占内切圆比例（越界拉回） | 0.955 |
| `TOUCH_SLOP` | 触摸判定容差倍数 | 1.55 |
| `addArcRow(...)` 的 `y0f/curvef/spanF` | 各行纵向位置 / 弧度 / 行宽占比 | 见源码注释 |

表盘特别小（如 1.2" 小圆屏）时可把 `MARGIN_FRACTION` 降到 0.04、`spanF` 提到 0.9。

## 自定义词库（v1.1）

打开手表上的「圆屏26键输入法」App → **导入词库**，选择 UTF-8 文本文件（`.txt`）即可，**导入立即生效，随时可重新导入更新，无需重装应用**。

文件格式（每行一条，可参考仓库内 `sample_dict.txt`）：

| 写法 | 示例 | 效果 |
|---|---|---|
| 单词一行 | `hello` | 输入 `hel` 联想 hello |
| 键 值（空格分隔） | `nihao 你好` | 输入 `nihao` 候选「你好」 |
| 键 TAB 值 TAB 词频 | `nihao<TAB>你好<TAB>500` | 词频越大候选越靠前 |
| 注释 | `# 注释` | 忽略 |

键入规则：导入词库后，字母先进入**组合串**（显示在空格键上），表盘顶部弧出现最多 3 个候选词——
点候选直接上屏；**空格** = 上屏首选候选 + 空格；**回车** = 上屏原始字母串；**退格**优先删组合串。
未导入词库时保持纯字母直通上屏。

### 词库工作原理

`WordStore` 把词库解析为 `(key, value, freq)`，按 key 排序后用二分查找做前缀匹配，
单次扫描上限 500 条再按词频排序，总词条上限 20 万，手表上内存与延迟均可控。

## 目录结构

```
app/src/main/java/com/example/roundkb/
├── RoundKeyboardView.java   # 核心：圆弧布局算法 + 边距限制 + 候选词弧 + 触控 + 绘制
├── WordStore.java           # 自定义词库：解析 / 二分前缀匹配 / 应用内导入更新
├── RoundIME.java            # InputMethodService，接 InputConnection
└── MainActivity.java        # 启用引导 + 词库导入 / 清空 / 统计
```

## 更新日志

- **v1.1**：新增自定义词库（应用内导入/清空/统计，导入即生效）、组合输入与顶部候选弧、LICENSE / 示例词库 / 开源仓库
- **v1.0**：圆弧排布 26 键、双重边距限制、Shift/大写锁定、符号层、长按退格

> 说明：字母层上屏为英文小写（Shift 转大写）。如需拼音联想可在 `RoundIME.onText()`
> 中接入输入引擎，布局层无需改动。
