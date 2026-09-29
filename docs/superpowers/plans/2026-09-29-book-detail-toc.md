# 书籍详情页 · 目录先行 实施计划（2026-09-29）

> **背景：** 前序会话（zcode `sess_3c24eea7`）在实施本需求时代理配额耗尽中断，仅完成侦察。本档由 Hermes 接手后补记，实施与本档同步完成。

**Goal:** 点击书本先进「书籍详情页」（目录先行），参考已有截图的信息结构，但视觉与交互全部采用本应用的设计语言（M3、大圆角、surfaceContainer 分层、悬浮胶囊）。

**来源需求（master 原话）:**「点击进去书本时候优先进目录，可以参考这个，但 ui 布局你优化一下用本应用的」。

**过渡上下文:** 前序会话已完成侦察结论——`openBook` 是导航收敛点一处改全家用；压缩包章节=子文件夹分组（`PageSources.buildChapters`）；书签跳转应保持直达阅读页。

## 设计决策

- **导航：** 新增路由 `book/{bookId}`；首页 / 书架 / 分组 / 分区列表四个入口统一改为打开详情页；书签跳转仍直达阅读页（跳页语义）。
- **目录加载：** 仅本地书在详情页打开数据源读章节（open 时已建索引），读完即关；远程书（webdav:// / gdrive://）不发起网络 IO，显示提示文案，目录进阅读器内查看。
- **起始页语义：** 新增 `restart` 路由参数与 `firstOpenStartPage` 纯函数——从头开始 > 显式跳页 > 续读进度。「继续阅读」= 续读；「从头开始」/ 点第一章 / 书签跳第 0 页 = 精确跳页（不沿用进度，对齐旧注释"0 表示从头读"的本意）。
- **详情页结构：** TopAppBar（返回 + 收藏心形）；封面 + 书名 + 格式/大小/页数；添加/阅读相对时间统计行；目录卡片（当前章节高亮、读过变淡、行尾页码、首末行圆角）；底部悬浮胶囊（从头开始 / 继续阅读主按钮 / 删除二次确认）。

## 实施清单

- [x] `ui/BookDetailViewModel.kt`：书名流、目录状态机（Loading/Loaded/Failed/Hidden）、收藏、删除、`currentChapterIndex`
- [x] `ui/BookDetailScreen.kt`：完整详情页 UI（含四态目录区与底部操作胶囊）
- [x] `ui/Navigation.kt`：BOOK_DETAIL 路由、四入口改道、`openBookAtPage` / `openBookFromStart` 助手、reader 路由加 `restart`
- [x] `reader/ReaderScreen.kt` + `reader/ReaderViewModel.kt`：`startFromBeginning` 参数与 `firstOpenStartPage`
- [x] 版本号 0.2.1 → 0.2.2（versionCode 4）
- [x] 测试：`ui/BookDetailTest.kt`（5 例）、`reader/ReaderStartPageTest.kt`（4 例）

## 验证

- [x] `:app:testDebugUnitTest` 26/26 通过（原 17 + 新增 9）
- [x] `:app:assembleDebug` / `:app:assembleRelease` 通过，产物已复制到 `outputs/`
- [x] `:app:lintDebug` 无错误（19 条既有警告，新增文件零告警）
- [x] APK 内容抽检：debug 含 `BookDetailScreen` 等新类；release（R8 混淆后）含"目录生成中"等新串
- [ ] 实机体验清单（见 `docs/verification/2026-09-29-book-detail-toc.md` 文末，需真机）

## 详情见

`docs/verification/2026-09-29-book-detail-toc.md`（验证记录与实机清单）。
