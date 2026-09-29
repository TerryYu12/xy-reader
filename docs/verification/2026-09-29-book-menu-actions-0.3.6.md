# 0.3.6 书本三点菜单扩展：重命名 / 刷新封面 / 自定义封面 / 转移书架 / 删除阅读记录 / 删除 — 验证记录（2026-09-29）

## 需求

> 「三点展开里要有重命名、刷新封面、自定义封面、转移书架、删除阅读记录、删除的功能」

现状（截图确认）：菜单只有「移动到分组…」+「删除」。本次扩展为六项。

## 实现

| 层 | 文件 | 改动 |
|---|---|---|
| 菜单 UI | `ui/BookGrid.kt` | 三点菜单六项：重命名… / 刷新封面 / 自定义封面… / 转移书架… / 删除阅读记录 / 删除（红色）。新增重命名对话框（预填书名、去首尾空白、空标题禁提交）与删除阅读记录确认框；「移动到分组…」按用户命名改为「转移书架…」（对话框标题同步） |
| 封面选择 | `ui/BookGrid.kt` | `GetContent("image/*")` 系统选图 → 交给仓库落盘（BookGrid 自带 launcher，三个宿主页面自动具备） |
| 数据层 | `core/Repository.kt` + `data/LibraryRepositoryImpl.kt` | 新方法：`renameBook`（trim、空忽略）/ `clearReadingHistory`（currentPage=0、lastReadAt=null，书与书签保留）/ `refreshCover`（重跑扫描生成链，含页数回填；**换时间戳文件名**防图片库旧缓存；失败保留旧封面）/ `setCustomCover`（解码→缩放到宽 512→JPEG 88→写入 `filesDir/covers/`→旧封面删除） |
| 反馈通道 | `ui/HomeScreen.kt` / `ui/ShelfScreen.kt`（ListScreen）/ `ui/GroupBooksScreen.kt` | 三处 BookGrid 接 `onMessage`；后两处补 SnackbarHost。提示文案：封面已刷新 / 封面已更新 / 已删除阅读记录 / 没能生成封面 / 封面设置失败 |

交互细节：

- **重命名**：菜单项 → 对话框预填当前书名 → 编辑 → 确定（trim 后落库）；列表即时更新。
- **刷新封面**：直接执行（无确认）；从书文件重新生成（含页数刷新）；成功后封面文件名变化保证 UI 立即刷新。
- **自定义封面**：系统图片选择器选图 → 缩小到宽 512 写入缓存；取消选择无副作用。
- **转移书架**：原「移动到分组」功能（未分组 + 各组单选），文案统一为「书架」。
- **删除阅读记录**：确认框后清零进度，回到未读状态；书籍、书签保留。
- **删除**：保持原确认框（移除记录/进度/书签，不动原文件）。

## 验证（53/53 全绿）

新增 6 条测试：

- `BookActionsRepoTest`（3 条，真 Room + Robolectric）：
  - rename trim 生效 & 空标题忽略；
  - clearReadingHistory：currentPage=0、lastReadAt=null、totalPages 保留；
  - setCustomCover：真实 PNG → 落盘 `-custom-` 文件 + 落库；**refreshCover 失败（书源缺失）时返回 false 且旧封面不被破坏**。
- `LibraryInteractionTest`（+3 条，真实 Compose 运行时）：
  - 三点菜单六项全部渲染；
  - 重命名对话框提交 trim 后标题；
  - 删除阅读记录先确认（取消不触发、确认触发一次）。
- 既有 47 条全部无回归（含 0.3.4/0.3.5 播放菜单、阅读弹层/配置页）。
- lint：0 errors。
- APK 抽检：aapt 核版本 `0.3.6 / versionCode 11`；debug/release dex 含
  `renameBook`/`clearReadingHistory`/`refreshCover`/`setCustomCover` 及新菜单文案。
- icon 预核：DriveFileRenameOutline / Autorenew / Image / History / DriveFileMove / DeleteOutline 全部实存。

## 实机清单（用户执行）

1. 任一封面三点菜单 → 六项齐全（截图对照）；
2. 重命名 → 改完列表标题即时变化（重进书库仍在）；
3. 刷新封面 → 底部提示「封面已刷新」且封面图变化（对 PDF/漫画类有效；失败会提示「没能生成封面」且封面不动）；
4. 自定义封面 → 选一张图 → 封面替换；再进书库仍是新封面；
5. 转移书架 → 选「未分组」或某分组 → 到对应分类可见；
6. 删除阅读记录 → 确认后进度角标消失（回未读）；
7. 删除 → 原确认流程不变。

## 诚实声明

刷新封面的**成功路径**依赖真实书源打开（本机测试仅覆盖失败保旧路径，靠编译+测试+真机清单兜底）；
封面替换后 Coil 即时刷新以「换文件名」方式保证，属工程手段，真机确认最终观感。

## 产物

- `outputs/app-release-0.3.6.apk`（22.3MB，md5 `3547c91a8e8719a0da0e959c30d32bec`）、
  `outputs/app-debug.apk`（40MB，md5 `f1f46956fb1a9ce35379344e7061bcd7`）。
- 抽检记录：debug/release dex 均含 `renameBook` / `clearReadingHistory` / `refreshCover` /
  `setCustomCover` 及菜单文案（重命名 / 刷新封面 / 自定义封面 / 转移书架 / 删除阅读记录）与
  反馈文案（封面已刷新）。
- lint：0 errors（19 既有 warnings）。
- 回滚点：`backups/`（本版改动：BookGrid 卡片菜单 + 数据层四方法 + 三处页面反馈通道）。
