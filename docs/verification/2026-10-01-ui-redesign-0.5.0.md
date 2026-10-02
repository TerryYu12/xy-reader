# 0.5.0 验证档 —— Codex UI 重设计落地（首页 / 阅读器 / 主题切换）

日期：2026-10-01
版本：**0.5.0 / versionCode 24**
交付物：`outputs/XY-READER-0.5.0.apk`（22,613,165 B；md5 `032281cf2ad9642a00b19d477b047973`）
设计源：`outputs/ui-preview/index.html`（Codex 产出、master 已验收）+ home-*.jpg / reader-*.jpg
任务书：`docs/superpowers/plans/2026-10-01-ui-redesign-landing.md`（含第二轮「全应用统一」任务区）

## 一、执行方式

Amadeus 主控 + 3 个子代理并行落码（文件互不重叠），主代理统一集成构建与真机验收：

| 子代理 | 范围 | 独占文件 |
|---|---|---|
| W-Theme | 手动主题切换地基 | `ui/ThemeMode.kt`(新)、`data/ThemePrefsStore.kt`(新)、`MainActivity.kt`、新单测 |
| W-Home | 首页版式 | `ui/HomeScreen.kt`、`ui/BookGrid.kt` |
| W-Reader | 阅读器工具栏 / 设置面板 / 亮度弹层 | `reader/ReaderScreen.kt` |

主代理补丁：`ui/Theme.kt` 的 `accentColor()` 跟随手动主题；首页分类胶囊未选中态=透明底+描边、「新建分组」=主色描边（对设计稿 `.chip` / `.chip.add` 修正）。

## 二、改动清单

- `ui/ThemeMode.kt`（新）：`ThemeMode{SYSTEM,DARK,LIGHT}` + `LocalThemeMode` / `LocalSetThemeMode`。
- `data/ThemePrefsStore.kt`（新）：DataStore `theme_mode`，默认 SYSTEM，非法值回退。
- `MainActivity.kt`：模式收集 → `darkTheme` → `CompositionLocalProvider` → `ArkTheme`。
- `ui/Theme.kt`：`accentColor()` 按生效主题取色。
- `HomeScreen.kt`：品牌顶栏（图标+XY-READER+搜索+主题钮+齿轮）、「继续阅读」焦点卡、「N 本书」+排序行、分类胶囊、空态/拖拽/快速阅读菜单保留。
- `BookGrid.kt`：爱心右上、格式角标左下常显、页码徽标移除、「分组 · 格式 · P% 已读」+3dp 进度条。
- `ReaderScreen.kt`：顶/底栏对齐设计（全宽/分割线/图标+小字标签）；设置面板标题行+✕+半屏（`weight(1f)`）；亮度独立浮动弹层（`.brightness-popover` 语义）；字体组六项完整标签。
- `app/build.gradle.kts`：0.5.0（vc 24）；新增 `ThemeModeRoundTripTest`。

**本轮明确跳过**（第二轮由 master 指示全应用统一，含：书架/分区/书签/设置/阅读配置/分组/详情/各弹层、默认封面新样式、图标底色=强调色；阅读器页眉/页脚装饰仍暂缓）。

## 三、自动化验证

- `:app:testDebugUnitTest`：**111 tests, 0 failures, 0 errors, 2 skipped**（胶囊微调后终版复跑）。
- 集成首轮编译即通过（三子代理产物零冲突）。
- `:app:assembleRelease` BUILD SUCCESSFUL（终版）；`:app:lintDebug` BUILD SUCCESSFUL（终版，9m56s）。
- aapt：`versionCode=24 versionName=0.5.0` + INTERNET 权限；签名 CN=XY Reader（同发布证书）。
- 疑点澄清：0.5.0 与 0.4.11 的 APK 总字节数相同（22,613,165）——逐条目 diff 证实为 **zipalign 对齐吸收**（classes.dex +10,287B 恰被后续 .so 对齐填充吸收；baseline.prof/profm 亦微变），非构建异常。

## 四、MuMu 真机实测（1080×2400，release 覆盖升级，数据保留）

截图目录：scratch `xyreader-lock-verify/shots/v050*`。

1. 首页深/浅两主题：品牌栏、搜索、主题钮（一键切换）、继续阅读卡、2 本书+排序、大封面卡（爱心右上/格式角标/进度条）、FAB 全在屏 ✓。
2. 分类胶囊样式（像素级核验）：选中=primaryContainer 填充；未选中=透明底+描边（深色下内部像素=背景色 (10,12,16)）；新建分组=主色 45% 描边 ✓。
3. 阅读器：顶部六项 + 底部工具（图标+小字标签）✓；设置面板半屏（约52%）+ 标题「阅读设置」+✕ + 字体六项（霞鹜文楷/朱雀仿宋在屏）✓；亮度弹层（跟随系统按钮、点外关闭）✓。
4. 锁定链路（0.4.11 语义回归）：菜单锁定 → 中央呼出解锁钮 → 解锁回归 ✓。

## 五、待 master 确认

1. 观感对照设计稿；2. 版本号 0.5.0；3. 是否 push + 发 Release（需批准）。
