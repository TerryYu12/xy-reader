# 任务：Codex UI 重设计落地（0.5.0 候选）

日期：2026-10-01 · 执行：Amadeus + 3 个子代理（W-Theme / W-Home / W-Reader）

## 背景

master 让 Codex 重做了 XY reader 的 UI 与版式，产出**网页预览**（已获 master 验收）：
`outputs/ui-preview/index.html`（唯一设计源，CSS+DOM 全在这）+ `home-*.jpg / reader-*.jpg / wide-dark.jpg`（渲染截图）。
Codex 明确未动正式代码（「未修改正式 ReaderScreen、BookGrid 或构建配置，也未运行 Gradle」）——本任务即「落实」。

**重要事实**：应用现有设计系统（明暗双色板 `ui/Theme.kt`、三款内置字体、悬浮胶囊底栏、锁语义 0.4.11）与设计稿同源；
Codex 的稿子是**在现有系统之上的版式升级**。因此落地 = 版式/组件级改动，不是重写。

## 范围（本波）

1. **主题切换**（新）：浅色/深色/跟随系统的手动切换 + 首页顶栏切换钮。（明暗色板已存在，缺手动开关与持久化。）
2. **首页**：品牌行 + 搜索 + 主题钮 + 齿轮；「继续阅读」焦点卡；书籍数量+排序行；书籍卡版式（爱心右上、格式角标、进度条、⋮ 于标题行）；FAB 造型。
3. **阅读器**：顶/底工具栏按稿整形（浅色主题下的栏色）；设置面板加标题行（阅读设置 + ✕）与固定半屏高度、确保改设置不丢面板滚动/分页位置；**亮度改为独立浮动弹层**（.brightness-popover）；字体组六项命名对齐（系统三款 + 霞鹜文楷 / MiSans / 朱雀仿宋，字体已内置）。
4. （可选项，风险高可跳过）随笔页面装饰：chapter-kicker（"XY-READER / 格式"）与页脚 "— 页码 —"——涉及 NovelPageSource 排版，必须保证 Novel* 全部测试绿，否则不做并记录。

**不在本波**：书架页/设置页/阅读配置页/分组的改版（设计稿 HTML 有草样，未在验收截图内）；宽屏版式（wide-dark.jpg）；已完成的图标与 0.4.11 锁语义改动。

## 冻结接口（子代理之间必须按此对齐）

W-Theme 创建 `app/src/main/java/com/xyreader/ui/ThemeMode.kt`：

```kotlin
enum class ThemeMode { SYSTEM, DARK, LIGHT }
val LocalThemeMode = staticCompositionLocalOf { ThemeMode.SYSTEM }
val LocalSetThemeMode = staticCompositionLocalOf<(ThemeMode) -> Unit> { {} }
```

- W-Home 在顶栏按钮处消费 `LocalThemeMode` / `LocalSetThemeMode`（`com.xyreader.ui` 包）。
- W-Theme 负责：DataStore 持久化（仿现有 reader prefs store 的写法，独立新 store）、MainActivity 收集并：
  `darkTheme = when(mode){SYSTEM→isSystemInDarkTheme(); DARK→true; LIGHT→false}`，`CompositionLocalProvider` 下发两个 local。

## 文件 ownership（互不越界）

| 子代理 | 独占文件 |
|---|---|
| W-Theme | `ui/ThemeMode.kt`(新)、`MainActivity.kt`、新 DataStore 文件（`data/` 下）、对应小单测（可选） |
| W-Home | `ui/HomeScreen.kt`、`ui/BookGrid.kt`（仅在必要时 + `ui/ShelfScreen.kt` 的兼容改动，需在报告注明） |
| W-Reader | `reader/ReaderScreen.kt`（可选：`core/NovelPageSource.kt` 等 + 相关测试，仅装饰元素用，绿不了就回退） |

铁律：**不许跑 gradle**（并发构建互踩；集成由 Amadeus 统一跑）；不许 commit；不许碰 outputs/、图标、lock 相关测试语义。

## 设计源导航（index.html，~122K 字符）

- 首页 CSS：`.topbar`@~5600 / `.continue-panel`@~8700 / `.cover`@~10700 / `.book-grid`@~14460 / `.book-card`@~14550 / `.mobile-nav`@~42300；
- 阅读器 CSS：`.reader-top`@~32750 / `.reader-controls`@~33240 / `.reader-content`@~33860 / `.chapter-kicker`@~34410 / `.reader-copy`@~34700 / `.reader-footnote`@~34970 / `.gesture-unlock`@~35450 / `.reader-range-line`@~36160 / `.reader-tool`@~36430；
- 面板 CSS：`.sheet`@~19740+ / `.pref-card`@~29080 / 亮度 `.brightness-popover`（搜 `.brightness-`）；
- 阅读器 DOM 生成：`class="reader-screen"`@~89050；首页 DOM：搜 `renderHome`/`class="book-grid"`。
- 截图审阅工具：`python F:\AI Flies\Hermes\cache\scratch\xyreader-ui\read_img.py <图片> "问题"`（a6api，省着用，每人 2-3 次）。
- 示例数据（demoBooks「潮汐之间」等）是演示用；真实数据用应用现有模型（BookEntity 等）。

## 集成与验收（Amadeus）

1. 三个流完成后统一：`:app:testDebugUnitTest` 全绿（110+）→ `assembleRelease` → `lintDebug`；
2. MuMu 真机安装对照设计稿截图（home 暗/明、reader 设置面板、亮度弹层、锁定态不回归）；
3. 版本号建议 **0.5.0**（交付时与 master 确认）；GitHub Release 需 master 批准。
4. 交付文档 `docs/verification/2026-10-01-ui-redesign-0.5.0.md`。

## 变更记录

- 2026-10-01：任务建立；三子代理派发。
- 2026-10-01（wave-1 收尾）：首页/阅读器/主题切换落地并验证（0.5.0 构建、MuMu 实测、111 单测）。

## 第二轮（master 追加指示，2026-10-01）

master 指示：**① 只用新主题、布局以新主题为准（全应用统一，不留旧版式）；② 默认封面风格也用新主题的；③ 外加强调色——图标背景色要和强调色一致。**

范围（设计源 index.html 全覆盖：shelfView / sectionView / detailView / settingsView / configView / groupsView / 各 modal / `.cover` 艺术）：

| 子代理 | 范围 | 独占文件 |
|---|---|---|
| W2-Shelf | 书架页（shelf-tile 磁贴+分组胶囊+page-head）、分区列表（全部/收藏/历史）、书签列表 | `ui/ShelfScreen.kt`、`ui/GroupBooksScreen.kt` |
| W2-Settings | 设置页（三组 settings-card + 行样式）、阅读配置页（config-tabs/pref-card 系）、分组管理 | `ui/SettingsScreen.kt`、`ui/ReaderConfigScreen.kt`、`ui/GroupManageScreen.kt` |
| W2-Covers | 默认封面（无封面书）：设计稿 `.cover` 艺术风（渐变+大字+标题+格式角标，数据驱动、不要伪造字段）用于卡片/焦点卡/详情封面；书籍详情页按 detailView 版式 | `ui/BookGrid.kt`、`ui/HomeScreen.kt`、`ui/BookDetailScreen.kt`、新建 `ui/BookCover.kt` |

强调色规则（全波适用）：图标块底色 = 取对应强调色 ~12-13% 透明度（对设计 `.tile-icon`/`.setting-icon` 模式）；色相映射按设计 settingsRows / shelf tiles（全部=primary、收藏=coral、历史=green、书签=gold；设置行按设计色表）。

纪律同 wave-1：禁跑 gradle、禁 commit、文件 ownership 不越界、保持测试依赖的 contentDescription/坐标不变；集成由主代理统一跑。

## 变更记录

- 2026-10-02：第二轮全部完成并验证（书架/设置系/分组/详情/默认封面；集成期修 BookCover 角标重叠、补 GroupBooksScreen 子页头；111 单测 + lint + MuMu 全页面截图全绿）。

## 反馈第二轮（2026-10-02，master 复核后）

- **强调色改为可选设置项**（理解纠偏：不是「图标块各自上色」）：新增 `ui/ThemeAccent.kt`（8 档：蓝紫/海蓝/青碧/翠绿/琥珀/珊瑚/玫红/紫罗兰，各含明暗两套 primary/container/on 系）；`ThemePrefsStore` 加 `accent_color`；MainActivity 下发 `LocalThemeAccent / LocalSetThemeAccent`；`ArkTheme(accent=)` 依档覆盖主色系。默认蓝紫＝原观感。设置页「外观 → 强调色」选择器：子代理实施中。
- **应用/品牌图标**：白色主体已从前景 PNG 单独提取（去内嵌海军蓝、柔化边缘；md5 验证替换入 res）；主页左上角图标＝白主体＋主色容器底，**实时跟随强调色**；启动器（桌面）图标为静态资源，底色用默认强调色——待 master 选：**A 亮阶 #A5B4FC / B 深阶 #3D4488**（预览图已发）。
- **继续阅读卡改紧凑横条**（对齐 `.continue-strip`）：缩略图 44×56 + 单行书名 + 「第 N 页 · P%」+ 3dp 进度条 + 小胶囊按钮；「新建分组」改**虚线描边**（对齐 `.chip.add`）。已落码。
- **仓库系四页（Repos/RemoteRepos/RepoConfig/Gdrive）+ 隐私/支持**统一到新设计语言：子代理实施中。
- 待 master 输入：① 图标底色 A/B；② 「UI 没完全沿用新 UI」的其余点名（我已知并已补：仓库系+隐私/支持）。

