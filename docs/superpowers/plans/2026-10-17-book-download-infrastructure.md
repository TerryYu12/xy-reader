# 书籍下载基础设施（来源无关）

日期：2026-10-17。基线：`564137b`（更新器分支 `codex/startup-update-background` 之上）。
前期调研：`docs/research/2026-10-17-library-catalogs.md`。

## 目标与边界

Master 2026-10-17 决策：**先只做下载基础设施，来源接口留空；不走自建书库（OPDS/Calibre/Kavita/Komga）那条路。**

因此本轮交付：任务队列、路径与命名、断点/恢复语义、内容校验、先落盘后入库、离线可读。
不交付：任何真实来源（OPDS / 书源 / 漫画源）、在线搜索结果页、下载管理界面。

## 分层

```
来源适配层（本轮只有接口，无实现）
  BookSource.acquire(entry) → Acquisition.DirectFile | Unsupported
        ↓  DownloadEntry（sourceId + itemId + format + version）
下载层（本轮交付）
  BookDownloadManager：身份与唯一命名 → 系统 DownloadManager 传输 → 进度/持久化
        ↓  校验通过（大小 + HTML/格式一致性）
入库层（复用现有导入管线）
  LibraryRepository.importDownloadedFile()：私有目录落盘 → 写库 → 生成封面/页数
        ↓
阅读层（现有，不改）
  ArchiveFactory 按 file:// 打开，离线可读
```

来源、下载、入库三层解耦：接一个新来源只需实现 `BookSource`，不碰下载与入库代码。

## 关键决定与理由

| 决定 | 理由 |
| --- | --- |
| 传输用系统 DownloadManager | 与更新器同一形态：进程被杀仍在跑、自带通知与进度、不新增依赖。代价是失败重试不保证字节续传（重新提交）。 |
| 落盘名 = 来源 ID + 条目 ID + 版本 + 身份哈希 | 现有 `importSharedFile` 按文件名落盘且「同名即覆盖」；下载必须绕开它，否则两个来源的同名书互相覆盖。标题只作展示名。 |
| 任务记录存 SharedPreferences（JSON 单条） | 与更新器一致；**不新增 Room 表，避免数据库迁移**（迁移需单独审批）。 |
| 校验只做「大小 + HTML/格式一致性」 | 书籍来源没有可比的官方哈希，签名也无从谈起。要拦的是最现实的坏响应：登录页/错误页被当成书下载下来。 |
| 明文 HTTP 只放行回环与 RFC1918 地址 | 自建目录常用局域网明文；公网明文传输书籍内容不可接受。 |
| 入库成功后才 remove 系统任务 | 书库私有副本是唯一事实来源；外部暂存副本不长期占用空间（与 APK 更新不同：APK 必须留着给安装器）。 |
| 失败/取消不删任何文件 | 与更新器同一保守策略：不隐式清理用户目录与系统任务，重试只新增任务。 |

## 新增/改动文件

- 新增 `core/BookSource.kt`：`DownloadEntry`、`Acquisition`、`BookSource` 契约（无实现）。
- 新增 `data/BookContentSniffer.kt`：文件头嗅探、HTML 判定、声明格式 ↔ 实际内容一致性表（从 `LibraryRepositoryImpl` 提取共用，避免两处规则漂移）。
- 新增 `data/BookDownloadManager.kt`：下载层 + `BookDownloadNaming` 命名 + 地址准入 `isAllowedDownloadUri`。
- 新增 `data/BookDownloadManagerTest.kt`：11 项。
- `core/Repository.kt` + `data/LibraryRepositoryImpl.kt`：新增 `importDownloadedFile(sourceFile, storedName, title)`（先 `.part` 后改名、按 uri 幂等）。
- `data/AppGraph.kt`：下载管理器全局单例。

## 后续（未排期）

1. 第一个真实来源适配器（形态待定：书源规则 / 自建目录 / 公开库）；`BookSource` 届时补目录浏览与搜索方法。
2. 在线书库界面：目录浏览、搜索、格式选择、下载队列与「已下载」状态。
3. 断点续传增强（若确有需要：自研 Range 下载 + 前台服务）。
4. 下载与更新共用的任务中心（若两套生命周期确实值得合并再合并，不提前抽象）。
