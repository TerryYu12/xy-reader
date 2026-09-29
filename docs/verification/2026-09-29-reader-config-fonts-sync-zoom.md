# 验证档：阅读配置分页改版 + 字体系统 + 手势/复制（0.3.0）

日期：2026-09-29
版本：versionCode 5 / versionName 0.3.0
产物：`outputs/app-debug.apk`（40.0MB）、`outputs/app-release-0.3.0.apk`（22.3MB）

## 一、本轮改动范围

### 1. 阅读配置管理页（ReaderConfigScreen 重写）
- 结构改为**顶部胶囊分组 + 横向分页**（翻页模式 / 页面 / 字体），不再整页上下滑动；
  点胶囊换组，与横滑分页双向同步。
- 组 1 翻页模式：翻页模式、**点击翻页开关（独立）**、双击放大开关、漫画方向。
- 组 2 页面：阅读背景（圆色块）、图片缩放、屏幕方向、阅读亮度（滑条+跟随系统）、屏幕常亮。
- 组 3 字体：**小说字体列表（系统 3 族 + 内置 3 款 + 导入字体，每行用该字体渲染名称作预览）**、
  字体粗细、小说字号；导入按钮（ttf/otf/ttc，SAF 选择器）+ 删除导入字体 + 授权注记。

### 2. 内置字体（打进 APK）
| 字体 | 文件 | 大小 | 授权 |
|---|---|---|---|
| 霞鹜文楷 Lite | res/font/lxgw_wenkai_lite.ttf | 13.9MB | SIL OFL 1.1 |
| MiSans Regular | res/font/misans_regular.ttf | 8.1MB | © 小米科技（免费商用，注明"使用了 MiSans 字体"） |
| 朱雀仿宋 Regular | res/font/zhuque_fangsong.ttf | 8.8MB | SIL OFL 1.1（璇玑造字） |

- 字体解析链：`核心/NovelFonts.kt`——导入字体 > 内置字体 > 系统族，失败逐级回退，绝不把异常带进排版。
- 生效链路：ReaderViewModel.buildNovelStyle → NovelStyle.typeface → NovelPageSource TextPaint；
  styleKey 含 novelCustomFont，切字体即时重分页。栏格保存：`novelCustomFont`（DataStore 新 key）。
- 导入字体存 `filesDir/fonts`，导入时以 Typeface.createFromFile 实测校验，坏文件删除并报错。

### 3. 上下模式「同步缩放」（关键修复）
- **问题**：旧实现每页各自 graphicsLayer 缩放 → 放大后相邻页重叠、脱节；且缩放中禁滚动。
- **方案**：整列布局级缩放——页项高度 = 未缩放高度 × scale（`aspectRatio(aspect / scale)`），
  页与页始终首尾相接、零重叠；缩放不改变页面顺序与阅读位置。
- 手势：双指捏合（质心为锚，滚动位置锚定）＋放大后水平拖动平移（钳制在溢出范围内）；
  纵向拖动仍由列表滚动承担——放大后照常上下阅读。
- 双击放大：单击点位置为锚，1x ↔ 2.5x 动画（仅上下模式；左右翻页模式保留原页内缩放）。
- 数学单元：`ContinuousZoomMath.topPixel/locate` + `anchoredScrollOffset/clampPanX`，
  新增 10 条单测。

### 4. 复制文字（仅文字小说）
- 底部工具栏新增「复制文字」按钮（仅对 TXT / 文字版 EPUB / MOBI 出现）。
- 弹层：当前页文字（按行布局还原）→ SelectionContainer 长按选片段；「复制本页」一键全页入剪贴板。
- 非文字源降级为空态提示；pageText 越界/失败返回 null。

## 二、验证矩阵（全部实跑）

| 项 | 命令 | 结果 |
|---|---|---|
| 编译 | `compileDebugKotlin` | ✅ BUILD SUCCESSFUL（仅既有 deprecation 警告） |
| 单测 | `testDebugUnitTest` | ✅ **36/36 通过**（26 既有 + 10 新增缩放数学） |
| 打包 | `assembleDebug assembleRelease lintDebug` | ✅ BUILD SUCCESSFUL，lint 0 error |
| dex 抽检 | debug/release 双包字节搜索 | ✅ ContinuousZoomMath / columnZoom / PageTextCopyDialog 均在包内 |
| 字体抽检 | unzip 列表 + resources.arsc | ✅ 三款字体均在（debug 见 res/font/*.ttf；release 经资源优化改名 res/i0.ttf 等，字节大小逐一吻合，arsc 逻辑名完好） |
| 版本 | aapt dump badging | ✅ versionCode 5 / 0.3.0 |

### 已知说明
- release 资源优化会把 res/font/*.ttf 改名为短路径（res/i0.ttf 等），**按文件名 grep 会误判丢失**，
  须按字节大小核对（8824084=朱雀 / 8122324=MiSans / 13872424=文楷）。
- 缩放锚定为近似实现（每帧重算像素锚点 + requestScrollToItem）；快速捏合可能有轻微漂移，实机可复验。

## 三、实机测试清单（需真机执行）

1. **配置页**：设置 → 阅读配置管理；顶部三个胶囊（翻页模式/页面/字体）可点、可横滑，内容随组切换。
2. **翻页模式组**：关「点击翻页」后点左右边缘只弹/收工具栏；开时左 1/3 上一页、右 1/3 下一页；滑动翻页不受开关影响。
3. **字体组**：
   - 点「霞鹜文楷 / MiSans / 朱雀仿宋」行，打开一本 TXT，正文即换字体（三款风格区分明显：楷体/黑体/仿宋）；
   - 「导入字体」选一个 ttf/otf → 列表出现该项并自动选中、正文生效；删除该字体 → 回到枚举字体；
   - 换字体时尽量保留阅读位置（字符锚点）。
4. **同步缩放（上下模式）**：
   - 上下模式（TXT 或漫画均可）双指捏合 → 整列一起放大；
   - **重点检查相邻两页交界处：无重叠、无空隙，依旧首尾相接**；
   - 放大后上下滚动顺滑；水平拖动可以左右看被裁掉的部分；
   - 双击放大 1x↔2.5x；设置里关「双击放大」后双击无效。
5. **复制文字**：打开 TXT → 底部工具栏出现「复制文字」→ 弹层长按选中片段；「复制本页」后到微信输入框粘贴验证。
6. 漫画/PDF 打开时**不应**出现「复制文字」按钮。

## 四、回滚点
- 配置页旧版备份：`backups/ReaderConfigScreen.kt.bak-0.2.2`；NovelFonts 预编译备份 `backups/NovelFonts.kt.bak-preCompile`。
- 0.2.2 安装包：`outputs/app-release-0.2.2.apk`。
