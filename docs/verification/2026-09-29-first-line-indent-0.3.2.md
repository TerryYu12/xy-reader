# 0.3.2 小说首行缩进开关 — 验证记录（2026-09-29）

## 需求

> 「关于小说阅读，最好在设置里加个首行缩进的开关，开启后每段首行缩减2字符」

## 实现

| 层 | 文件 | 改动 |
|---|---|---|
| 偏好 | `core/ReaderPrefs.kt` | 新字段 `novelFirstLineIndent: Boolean = true`（默认开启，可关） |
| 持久化 | `data/ReaderPrefsStore.kt` | KEY `novel_first_line_indent` 读写 |
| 排版 | `archive/NovelPageSource.kt` | `NovelStyle.firstLineIndent`；顶层 `buildIndentedParagraph(paint, text)`：`LeadingMarginSpan.Standard(2全角字宽 − 段落已有前导空白宽, 0)` 挂首行边距；缩进量 ≤0 时不挂 span |
| 重分页 | `reader/ReaderViewModel.kt` | `styleKey` 加入该字段；`buildNovelStyle` 透传 |
| 入口 | `ui/ReaderConfigScreen.kt` | 字体组新增「首行缩进」SwitchCard（带说明文案） |
| 入口 | `reader/ReaderScreen.kt` | 阅读界面快速面板（字号滑杆下方）新增开关行 |

### 关键设计决定

1. **只挂 span、不改文本**：缩进通过 `LeadingMarginSpan` 实现（AOSP `StaticLayout.generate()` 对
   LeadingMarginSpan 有逐段专门处理，依据 android-14.0.0_r1 L785）。段落文本一个字符都不动，
   因此页码字符锚点（`pageStartCharOffset`）、「复制文字」还原偏移全部保持原样，进度不飘。
2. **不叠加**：缩进量 = 2 全角字符宽 − 已有前导空白实测宽。源文本已是「　　」开头（常见中文排版）
   → 缩进量为 0；总缩进恒为 2 字符宽，不会变 4 字符。
3. **默认开启**：中文小说标准排版；设置里可随时关闭，关闭立即重分页恢复顶格。

## 验证（本机可跑的都跑了）

- 单测 **40/40 全绿**（上一版 38 + 本版新增 2）：
  - `firstLineIndentSpanAddsTwoCharMarginWithoutChangingText`：普通段落挂 2 字宽 span（38px@19sp）
    且 `toString()` 与原文逐字节相同；「　　」开头不叠加（不挂 span）；半角空格按总宽对齐。
  - `indentSettingKeepsPageTextAndAnchorClean`：缩进模式正常分页、页 0 锚点仍为 0、
    「复制文字」输出不含缩进填充字符、渲染位图尺寸正确。
- lint：**0 errors** / 19 既有 warnings。
- APK 抽检：`aapt` 核版本 `0.3.2 / versionCode 7`；debug 与 release dex 均含
  `首行缩进`、`novel_first_line_indent`、`buildIndentedParagraph`。
- md5：build 与 outputs 拷贝一致（debug `82a4dce1…`、release `5a7c8d03…`）。

### 诚实声明：本机做不了的部分

Robolectric 环境**不执行** StaticLayout 的 LeadingMarginSpan/setIndents，且把 `\u3000`
渲染成可见块（实测探针：四方案像素位移全为 0、span 布局 lineLeft 恒 0）。因此「视觉上是否
右移 2 字符」**无法在本机自动化验证**，已通过 AOSP 源码确认机制、通过 span 接线与文本零偏移
不变式锁住逻辑；最终像素效果需真机确认（见下）。

## 实机清单（用户执行）

1. 阅读配置管理 → 字体组：开关默认开，小说每段首行应空 2 格；关掉 → 立刻恢复顶格。
2. 找一本正文已带「　　」缩进的书：开启后总缩进仍是 2 格（不出现 4 格）。
3. 阅读界面点工具栏调出快速面板：字号下方「首行缩进」开关可用、与配置页状态同步。
4. 复制文字：输出应不含任何填充空格；翻页/进度与开启前一致。

## 产物

- `outputs/app-release-0.3.2.apk`（22.3MB）、`outputs/app-debug.apk`（40MB）
- 回滚点：`backups/`（本版改动均为小步 patch，可直接逆改）
