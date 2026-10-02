# 0.5.0 验证档 —— Codex UI 重设计落地（第一波 + 第二波）

日期：2026-10-01/02
版本：**0.5.0 / versionCode 24**
交付物：`outputs/XY-READER-0.5.0.apk`（22,645,933 B；md5 `0c4298465a8d93cdd87d8156d18f645a`）
设计源：`outputs/ui-preview/index.html`（Codex 产出、master 已验收）+ home-*.jpg / reader-*.jpg
任务书：`docs/superpowers/plans/2026-10-01-ui-redesign-landing.md`（含第二轮任务区）

## 〇、总览：两波六子代理并行落地

| 波次 | 子代理 | 范围 | 独占文件 |
|---|---|---|---|
| 一 | W-Theme | 手动主题切换地基 | `ui/ThemeMode.kt`(新)、`data/ThemePrefsStore.kt`(新)、`MainActivity.kt`、新单测 |
| 一 | W-Home | 首页版式 | `ui/HomeScreen.kt`、`ui/BookGrid.kt` |
| 一 | W-Reader | 阅读器工具栏/设置面板/亮度弹层 | `reader/ReaderScreen.kt` |
| 二 | W2-Shelf | 书架页/分区列表/书签页 | `ui/ShelfScreen.kt` |
| 二 | W2-Settings | 设置页/阅读配置页/分组管理 | `ui/SettingsScreen.kt`、`ui/ReaderConfigScreen.kt`、`ui/GroupManageScreen.kt` |
| 二 | W2-Covers | 默认封面 + 书籍详情页 | `ui/BookCover.kt`(新)、`ui/BookGrid.kt`、`ui/HomeScreen.kt`、`ui/BookDetailScreen.kt` |

纪律：子代理只碰各自文件、禁跑 gradle/禁 commit；主代理统一集成构建、补丁与真机验收。
主代理补丁：`Theme.kt` accentColor 跟随手动主题；首页分类胶囊描边风格；`GroupBooksScreen` 子页头（第二波补漏）；`BookCover` 格式角标改流内排版（修与标题重叠）；集成期修两处编译错（`HorizontalDivider` 缺 import、`r.font` 笔误）。

## 一、第一波（主题 / 首页 / 阅读器）

- `ui/ThemeMode.kt`（新）：`ThemeMode{SYSTEM,DARK,LIGHT}` + `LocalThemeMode` / `LocalSetThemeMode`（冻结接口）。
- `data/ThemePrefsStore.kt`（新）：DataStore `theme_mode`，默认 SYSTEM，非法值回退。
- `MainActivity.kt`：模式收集 → `darkTheme` → `CompositionLocalProvider` → `ArkTheme`；`ui/Theme.kt` accentColor 按生效主题取色。
- `HomeScreen.kt`：品牌顶栏（图标+XY-READER+搜索+主题钮+齿轮）、「继续阅读」焦点卡、「N 本书」+排序行、分类胶囊、空态/拖拽/快速阅读菜单保留。
- `BookGrid.kt`：爱心右上、格式角标左下常显、页码徽标移除、「分组 · 格式 · P% 已读」+3dp 进度条。
- `ReaderScreen.kt`：顶/底栏对齐设计（全宽/分割线/图标+小字标签）；设置面板标题行+✕+半屏（`weight(1f)`）；亮度独立浮动弹层；字体组六项完整标签。

## 二、第二波（master 追加指示：全应用统一 / 默认封面新样式 / 图标底色=强调色）

- **书架页**（`ShelfScreen.kt` 重写）：page-head（eyebrow+h1+添加仓库复用既有扫描入口）、统计卡行、`shelf-tile` 磁贴（图标块=强调色 13%、计数「N 项」）、分组胶囊（无分组整区隐藏）、「最近阅读」三本；分区列表/书签页改子页头版式；`GroupBooksScreen` 同版式补齐。
- **设置系**：设置页三组卡片（设置行=38dp 强调色图标块 12% + 标题/副标题/尾），色相严格对设计色表（本地仓库→primary、Drive→coral、标签→gold、阅读配置→cyan…）；阅读配置页 `.pref-card` 体系（choice/pill/switch/range/色块）；分组管理页卡内行式 + 新建软按钮。
- **默认封面**（`ui/BookCover.kt` 新）：7 组设计渐变（tide/orbit/harbor/moon/rain/letters/unread，色值逐条照抄），按书名 hash 确定性选色；结构=顶部小字行+大字符号+书名+格式角标（角标**流内排版**，修掉预览期发现的与标题重叠）；用于卡片回退/焦点卡小封面/详情大封面；有真实封面零改动。
- **书籍详情页**：subpage-head（返回+「书籍详情」+格式·分组）+大封面+stat 胶囊+主/次操作（继续阅读/从头开始/收藏/删除）+章节行（当前章「上次读到」高亮）；移除旧底部悬浮胶囊栏（功能内联保留）。

## 三、自动化验证（全部在终版代码上）

- `:app:testDebugUnitTest`：**111 tests, 0 failures, 0 errors, 2 skipped**（含 BookDetailTest 5/0、LibraryInteractionTest 7/0、ReaderConfigCapsuleTest 2/0）。
- `:app:assembleRelease`：BUILD SUCCESSFUL（终版 10m25s）。
- `:app:lintDebug`：BUILD SUCCESSFUL（终版 5m4s）。
- aapt：`versionCode=24 versionName=0.5.0` + INTERNET 权限；签名 CN=XY Reader（同发布证书，可覆盖升级）。
- 疑点澄清（第一波）：0.5.0 与 0.4.11 APK 总字节曾巧合相等——逐条目 diff 证实为 **zipalign 对齐吸收**（dex +10,287B 恰被后续 .so 对齐填充吸收），非构建异常。

## 四、MuMu 真机实测（1080×2400，release 覆盖升级，数据保留）

截图目录：scratch `xyreader-lock-verify/shots/v050*`。

1. 首页深/浅两主题：品牌栏/搜索/主题钮一键切换/焦点卡/计数排序/大封面卡/FAB ✓。
2. 分类胶囊（像素级）：选中=primaryContainer；未选中=透明底+描边；新建分组=主色 45% 描边 ✓。
3. 阅读器：工具栏图标+小字标签 ✓；设置面板半屏+标题+✕+字体六项 ✓；亮度弹层 ✓；锁定链路回归 ✓。
4. **书架页**：统计卡、「N 项」磁贴、（无分组时）分组区自动隐藏 ✓。
5. **设置页 / 阅读配置页 / 分组管理**：结构完整、层级正常（读图粗检「结构正常」）✓。
6. **默认封面真机验证**（构造法：导入首页损坏的 CBZ → 封面生成失败 → coverPath 为空）：网格卡显示渐变艺术封面（HARBOR 调色板像素核验 ✓、无渲染异常）；详情大封面角标/标题/书名三行垂直分离（修复后读图复核 ✓）。
7. 书籍详情页（test4p/broken 两本）：新版式在屏、章节/动作齐全 ✓。

## 五、已知剩余（供 master 决策）

- 未统一页面：仓库管理系 4 页（Repos/RemoteRepos/RepoConfig/Gdrive）+ 隐私/支持页仍为旧版式（次级页面）。
- 阅读器页眉/页脚装饰元素（设计稿有）仍暂缓（涉及 NovelPageSource 排版，风险控制）。
- 三处文件私有 SubpageHead 与书架 `ShelfSubpageHead` 可后续抽到公共组件；`NovelSpacingControls` 未套 `.pref-card` 外观。

## 六、待 master 确认

1. 观感对照设计稿（重点：书架/设置/详情/默认封面）；2. 版本号 0.5.0；3. 是否 push + 发 Release（需批准）。
