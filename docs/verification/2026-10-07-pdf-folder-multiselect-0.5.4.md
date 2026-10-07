# 0.5.4 文件夹 PDF 合集 + 书库多选 — 验证记录（2026-10-07）

## 需求

> 「mh-ark 可以导入文件夹后在同一个文件夹里的 pdf 就是一本书或者漫画，我希望我们有这个功能」
> 「另外需要新增多选移动删除等功能，代码交给 sonnet」

确认的取舍：
- 按仓库设置开关，默认开启，升级前已有的仓库也开启；
- 仓库根目录下的 PDF 不合并；
- 子文件夹里只有 1 个 PDF 时，仍作为普通 PDF 书入库；
- 只合并 PDF，压缩包、EPUB、TXT 等仍各自成书。

## 实现

| 功能 | 文件 | 改动 |
|---|---|---|
| PDF 合集 · 扫描 | `data/LibraryScanner.kt` | 新增规则 c：子文件夹（depth > 0）直接包含 2 个及以上非隐藏 PDF，且仓库开关打开时，整个文件夹入库为一本 `PDF_FOLDER`。书名取文件夹名，大小为各 PDF 之和，封面文件名约定同样生效。判定逻辑抽成 `shouldMergeFolderPdfs` |
| PDF 合集 · 数据 | `core/BookFormat.kt`、`core/Book.kt`、`data/ArkDatabase.kt`、`data/LocalRepoDao.kt`、`core/Repository.kt` | 新增格式 `PDF_FOLDER`。`local_repos` 表新增 `mergeFolderPdfs` 列，数据库从 v6 迁移到 v7，默认值为 1 |
| PDF 合集 · 页面源 | `archive/PdfFolderPageSource.kt`、`archive/PdfFolderMath.kt` | 打开时逐个探测 PDF 页数，损坏、加密或 0 页的跳过；每个 PDF 是一章，按文件名自然排序。子 PDF 按需打开，最多同时打开 2 个（LRU）；取子源与渲染用同一个 Mutex 串行 |
| PDF 合集 · 路由与阅读器 | `core/ArchiveFactory.kt`、`reader/ReaderViewModel.kt` | 本地书路由到 `PdfFolderPageSource`，WebDAV / Google Drive 抛「暂不支持」。高清预缩跳过 PDF 合集 |
| PDF 合集 · 界面 | `ui/RepoConfigScreen.kt`、`ui/BookGrid.kt`、`ui/ReposScreen.kt` | 「配置仓库」新增「同文件夹 PDF 合并为一本书」开关；格式角标显示「PDF合集」 |
| 多选 · 数据 | `data/BookDao.kt`、`data/GroupDao.kt`、`core/Repository.kt`、`data/LibraryRepositoryImpl.kt` | 新增批量 `setFavorite` / `deleteBooks` / `clearReadingHistory` / `moveBooksToGroup`，每 500 个 id 一批，在事务内提交 |
| 多选 · 网格 | `ui/BookGrid.kt` | 新增 `BookSelectionState`，详见下方说明 |
| 多选 · 首页 | `ui/HomeScreen.kt` | 多选时隐藏「开始阅读」悬浮按钮 |

`ui/BookGrid.kt` 的多选交互：
- 进入：长按封面后不拖动直接松手，或在三点菜单点「多选」；
- 选择条：退出、已选 N 本、全选 / 全不选；
- 底部操作栏：转移书架、收藏 / 取消收藏、清除记录、删除，其中删除和清除记录需要二次确认；
- 返回键退出多选；
- 长按后手指移出点按容差（touchSlop）才算拖动，拖动仍可排序、移组或拖到删除区。

分工：两个 Sonnet 子代理在独立 worktree 里并行写代码；主会话负责合并、逐文件审查和集成。

## 验证

**本地**：云环境的网络策略拦截了 `dl.google.com`，装不上 Android SDK，所以没有做完整的本地 Gradle 构建。两个子代理改用与项目相同的 Kotlin 2.0.21（K2）编译器加桩，做了部分编译：
- PDF 合集：
  - `PdfFolderPageSource`、`LibraryScanner`、`ArchiveFactory` 等核心源码对 Robolectric android-all 编译通过；
  - `PdfFolderMathTest` 16 项、`FolderPdfMergeRuleTest` 8 项运行通过；
  - 用假目录树跑真实的 `scanRepo`，8 个场景全部符合预期；
  - 迁移 SQL 用 sqlite 3.45 验证过。
- 多选：`BookGrid`、仓库接口、DAO 以及测试新增部分做了桩编译，0 错误；Compose 的真实签名由 CI 把关。

**CI**：[Android CI #23](https://github.com/TerryYu12/xy-reader/actions/runs/37579642176)，通过 workflow_dispatch 在分支 `claude/practical-rubin-mtg4da` 上运行，结果见下一节。

**新增测试**：
- PDF 合集：`PdfFolderMathTest`（16）、`FolderPdfMergeRuleTest`（8）、`RepoPdfMergeMigrationTest`（1）；
- 多选：`BookActionsRepoTest` 新增 5 项、`BookSelectionStateTest`（8）、`LibraryInteractionTest` 新增 9 项。

### CI 结果

运行 #23 对应 commit `ceeb5a5`，即 PDF 合集与多选合并后的状态：
- 单元测试：`:app:testDebugUnitTest` BUILD SUCCESSFUL，用时 2 分 8 秒；新增与原有测试全部通过。
- Release：`:app:assembleRelease` BUILD SUCCESSFUL，用时 4 分 1 秒；使用正式签名，`lintVitalRelease` 通过。
- 编译警告：只有项目里原本就有的那几类弃用提示。新增的一处 `Icons.Outlined.DriveFileMove` 与原有的「转移书架」菜单用法相同。
- APK 工件：`XY-READER-0.5.4`，22,330,980 bytes，[下载](https://github.com/TerryYu12/xy-reader/actions/runs/37579642176/artifacts/11463829040)，保留 90 天。

## 实机清单（用户执行）

1. 在本地仓库的子文件夹里放 3 个 PDF（如 第1卷.pdf / 第2卷.pdf / 第10卷.pdf），刷新仓库后应变成一本「PDF 合集」，书名为文件夹名；
2. 详情页和阅读器目录显示 3 章，顺序为 第1卷、第2卷、第10卷；跨文件翻页连续，进度按全书计算；
3. 仓库根目录的 PDF 仍各自成书；只有 1 个 PDF 的子文件夹仍是普通 PDF 书；
4. 进入「配置仓库」，关闭「同文件夹 PDF 合并为一本书」，保存并刷新后，应恢复为逐个 PDF；
5. 首页长按封面后松手，进入多选并显示「已选 1 本」；点其他封面可勾选，「全选 / 全不选」可用；
6. 批量转移书架、收藏、清除记录、删除各执行一次，结果正确且有提示；返回键能退出多选；
7. 首页和分组页长按并拖动：排序、拖到分组、拖到删除区仍正常；「全部 / 收藏 / 历史」列表页长按松手同样进入多选。

## 已知限制

- 已有仓库也默认开启合并：升级后刷新仓库时，子文件夹里原本逐个入库的多个 PDF 会被一本合集替换，它们原来的阅读进度、书签和分组不会保留。不想合并的仓库，请先关掉开关再刷新。
- 合集里的 PDF 增删后，刷新只更新大小和书名；页数在下次阅读时更新，封面需手动「刷新封面」。
- 远程仓库（WebDAV / Google Drive）不做 PDF 合并。
- 多选「删除」只移除书库记录，与单本删除一致；本地仓库里的文件仍在，下次刷新仓库时会重新入库。
- 列表页不支持拖动：长按后再移动手指会放弃这次长按，这一下也不会滚动列表。
- 本记录不代替真机验证，真机清单尚未执行。
