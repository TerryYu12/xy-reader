# 阅读与书架改进实施计划

> **For agentic workers:** 由主会话调度两名 gpt-6-luna / xhigh，按文件所有权实施；主会话审查与统一构建。用户已指定子代理执行，不再询问执行方式。

**Goal:** 小说字体字号可调，竖向阅读连续，首页可持久重排和分组，删除与收藏入口符合用户要求。

**Architecture:** 保留 PageSource → ReaderViewModel → Compose 架构；复用 LibraryRepository 的一书一组接口。首页顺序使用独立、顶层单实例 DataStore，避免修改现有 Room v5。

**Tech Stack:** Kotlin 2.0.21、Compose BOM 2024.12.01、Room 2.6.1、Gradle 8.10.2、JDK 17。

**Spec:** 本文下方需求与验收表；交接来源 `C:\Users\11\AppData\Local\Temp\xy-reader-handoff.md`。

## 约束

- 源码位置 `F:\AI Flies\hermes work\XY reader`，不是 Git 仓库；禁止覆盖无关源码。
- 中文界面；保留现有阅读、目录、书签、远程来源和仓库功能。
- 删除只移除书库记录，确认文案说明原文件保留；菜单和拖入删除区均需二次确认。
- 主会话统一跑 Gradle；子代理不同时构建。
- 编译、自动化、设备运行结果分别记录。当前 adb 无设备且 SDK 无 emulator，不能声称实机流畅度已通过。

## 阅读器

责任文件：`reader/ReaderScreen.kt`、`reader/ReaderViewModel.kt`、`archive/NovelPageSource.kt`、`archive/PdfPageSource.kt`、`core/ReaderPrefs.kt`、`data/ReaderPrefsStore.kt`、`ui/ReaderConfigScreen.kt`；必要时增补 PageSource 默认几何接口。

- [x] 在阅读设置与阅读器中提供字体、字号入口，配置写回 DataStore。
- [x] 字体选择进入实际 TextPaint；重新分页用文本锚点保留位置，避免仅沿用页码。
- [x] 竖向模式使用无额外页间距的连续列表，页面按宽高比决定高度；横向保留 Pager。
- [x] 缓存按阅读实例隔离，逐页状态不无限持有位图；关闭与渲染串行协调；旧任务不覆盖新样式。
- [x] 移除退出阅读时主线程 runBlocking，保存最终进度仍需可靠执行。

验收：相同文本调大字号页数增加；改变字体能进入渲染；改设置后当前文本位置附近继续；快速切背景/字号不串页；PDF相邻页面无视口高度补白；连续滑动后内存不随已读页数无限增长。

## 首页与书架

责任文件：`ui/HomeScreen.kt`、`ui/ShelfScreen.kt`、`ui/BookGrid.kt`、`ui/GroupBooksScreen.kt`、`ui/GroupManageScreen.kt`、`ui/Navigation.kt`；新增 `data/LibraryLayoutStore.kt` 与必要 UI 辅助文件。

- [x] 首页长按拖动排序，移动时仅改内存，松手写顺序；重启恢复，搜索不覆写全库顺序。
- [x] 首页展示全部/未分组/自定义分组与创建入口，菜单与拖动均可移入或移出分组。
- [x] 拖动坐标统一，长列表边缘滚动，正常单指滚动与点击打开保留。
- [x] 书名后的三点菜单含删除，拖到删除区同样弹确认；取消无数据修改。
- [x] 封面左下角常驻可点击收藏爱心，收藏与未收藏状态明确。
- [x] 书架入口取消固定108dp高度；小屏、大字号下文本完整，容器可滚动。

验收：A/B/C拖成C/A/B后重启顺序一致；搜索只见A/C时调整不丢B；新建组后用菜单和拖动分别加入/移出；取消删除保留书籍；收藏按钮不误触打开；320dp宽、字体倍率2.0时全部/收藏/历史/书签可读。

## 主会话验证

- [x] 阅读交接文件、定位项目、检查构建配置与设备。
- [x] 基线 assembleDebug、lintDebug、testDebugUnitTest，记录原有问题。
- [x] 添加针对小说分页/锚点、字体偏好往返、顺序合并的回归测试。
- [x] 审查拖动状态、取消操作、异常处理、UI尺寸和生命周期。
- [x] 统一执行以下命令；处理失败后重跑受影响检查（2026-09-28 全部通过，见 docs/verification/2026-09-28-round1.md）：

```powershell
& 'C:\Users\11\.zcode\workspace\default\tools\gradle-8.10.2\bin\gradle.bat' :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices -l
```

- [x] 把可安装 APK、验证结果和下一轮实机清单复制到本次任务 outputs（`outputs/app-debug.apk` + `docs/verification/2026-09-28-round1.md`）。

下一轮优先实机验证：TXT/EPUB/PDF各一本，普通屏与窄屏、大字号、长书滑动、后台恢复、远程书断网恢复。PDF文件自身白边与分页容器补白分开判断；原文件白边裁剪另列后续功能。
