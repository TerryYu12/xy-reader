# 0.5.6 超低亮度 + 压缩包诊断日志 — 验证记录（2026-10-11）

## 需求来源

BUG 反馈仓库 `xy-reader-feedback` 里共 2 条真实上传：

| Issue | 版本 / 设备 | 内容 | 处理 |
|---|---|---|---|
| #3 `20261010-172409-yhl1` | 0.5.5 · OnePlus PLQ110 · Android 16 | 「亮度要能比系统最低亮度还低」；日志里另有两本 CBZ 封面/页数生成失败（`压缩包内没有图片页面`） | 亮度：本次实现。CBZ：只补诊断，见下 |
| #2 `20261007-075438-s329` | 0.5.4 · 模拟器（x86_64 + 转译） | 描述只有「beta」，是测试提交 | 无具体问题，不处理 |

#1 是 Worker 部署时的 curl 自测。

## 实现

| 部分 | 文件 | 要点 |
|---|---|---|
| 亮度映射 | `core/BrightnessMath.kt`（新，无 Android 依赖） | 亮度取值 `-0.4..1`：`0.01..1` 是窗口亮度，与历史版本一致；负值区窗口亮度取 0.01，再叠加黑色遮罩，最深 85% 不透明（约系统最低亮度的 15%）。同时提供百分比文案和「继续向左拖」提示 |
| 遮罩 | `reader/WindowDim.kt`（新）、`reader/ReaderScreen.kt` | 主窗口用 `decorView.foreground` 盖遮罩，不拦截触摸，工具栏和亮度浮层一并变暗；离开阅读器时清除。目录弹窗、阅读设置面板、复制文字弹窗是独立窗口，各自在内容里调用 `DimThisWindow` |
| 滑条 | `reader/ReaderScreen.kt`、`ui/ReaderConfigScreen.kt` | 范围改为 `BrightnessMath.MIN..MAX`；负值区显示 `-N%` 和「比系统最低亮度再暗 N%」 |
| 存取 | `data/ReaderPrefsStore.kt`、`core/ReaderPrefs.kt` | 读写都按 `BrightnessMath.clamp` 约束，NaN 视为未设置。旧版本读到负值会回落到 0.01 |
| 诊断 | `archive/EntrySummary.kt`（新）、`archive/PageSources.kt`、`Zip/Rar/SevenZip/TarPageSource.kt` | 「没有图片页面」时附上条目数、目录数、被排除数和扩展名分布。只含数量和扩展名，非 ASCII 字符变 `?`，超长扩展名截断，不带出文件名 |

## 验证

- **本地（纯逻辑）**：云环境拦截了 `dl.google.com`，装不上 Android SDK，没有做本地 Gradle 构建。用 Kotlin 2.0.21 编译器加 JUnit 4.13.2 单独跑了两组不依赖 Android 的测试，共 13 项通过：
  - `BrightnessMathTest`：8 项。覆盖普通区与历史行为一致、跟随系统、负值区压最低窗口亮度并叠加遮罩、遮罩单调且封顶、0 附近连续、非法输入、文案和提示；
  - `EntrySummaryTest`：5 项。覆盖空包、目录与被排除计数、不泄露文件名和非 ASCII 扩展名、扩展名种类上限、反斜杠目录。`isExcludedEntry` 用的是从 `PageSources.kt` 原样抽出的一份。
- **CI**：[Android CI #31](https://github.com/TerryYu12/xy-reader/actions/runs/38147483855)（分支 `claude/dazzling-bardeen-dm3qgq`，commit `bf5d412`，手动触发）全部通过。其中单元测试步骤约 2 分 26 秒，Release APK 构建成功，工件 `XY-READER-0.5.6`，22,385,025 bytes。
- **新增 Robolectric 测试**：`PreferencesRoundTripTest.brightnessRoundTripsIncludingBelowSystemMinimum`，覆盖负值往返、越界写入被约束、null 仍是跟随系统。只在 CI 里跑过。

## 没有验证的部分

- **没在真机上看过实际效果**。`decorView.foreground` 在 OnePlus / Android 16 上是否如预期变暗，没有实测；遮罩最深 85% 这个值也只是估计，要看真机夜间观感再调。
- **阅读设置面板（`ModalBottomSheet`）的遮罩**依赖 `LocalView.current` 就是面板所在的视图。这点没在真机上确认；如果不生效，表现是面板在超低亮度下偏亮，不影响功能。面板打开时，主窗口遮罩下的内容还会被面板自带的半透明背景再压暗一层，这是预期的。
- **CBZ「没有图片页面」的根因仍未确认**。这次没有改识别规则，只补了诊断；下次再收到这类反馈，日志里会有扩展名分布。猜测之一是 JPEG XL（`.jxl`），但没有证据。
- **工件的签名**：CI 在没配置签名 secrets 时回退调试签名，这次没有确认用的是哪一种。

## 已知但未处理

- 0.5.4 模拟器日志（#2）里有一条 `System: A resource failed to call close.`，说明某处资源没有关闭。查了扫描、封面、PDF 探测等路径，没找到确定的泄漏点，没有凭猜测改动。
- 同一份日志里 `OnBackInvokedCallback is not enabled for the application`：应用没有开启预测性返回。目前 `targetSdk = 34`，不影响功能，没有改。
