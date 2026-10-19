# 书籍下载基础设施 验证记录

日期：2026-10-17。基线：`564137b`（分支 `codex/startup-update-background`，与更新器改动同一工作区）。
计划：`docs/superpowers/plans/2026-10-17-book-download-infrastructure.md`。

范围（Master 决策）：**只做下载基础设施，来源接口留空；不走自建书库（OPDS/Calibre/Kavita/Komga）。**

## 结论

代码与自动化验证通过；**没有在真机上、也没有接任何真实来源**跑过整条链路，因此不能称为「功能已交付」。
本阶段交付的是可复用的下载/校验/入库底座，来源与界面留待后续。

## 已实测

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 全量单元测试 | `./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain` | `BUILD SUCCESSFUL in 2m 6s`；**63 个 suite、437 项、0 failures、0 errors、4 skipped**（基线 61/422/0/0/4 → 新增 2 suite、+15 项） |
| 新增：下载层 | `--tests "com.xyreader.data.BookDownloadManagerTest"` | 11 项，0 失败 |
| 新增：入库层 | `--tests "com.xyreader.data.*"` | `DownloadedImportTest` 4 项，0 失败；同包合计 87 项全绿 |
| Lint | `./gradlew.bat :app:lintDebug --no-daemon --console=plain` | `BUILD SUCCESSFUL in 5m 51s`；0 errors、21 warnings（与改动前同数）；**改动文件 0 issue** |

日志：`outputs/` 之外的本机临时目录（`F:\AI-hub\agent\Hermes\cache\scratch\xy-reader-uitest\`）——`book-dl-final2.log`（全量）、`book-dl-test4.log`（下载层）、`book-dl-test5.log`（data 包）、`book-dl-lint.log`。

### 覆盖的行为

下载层（`BookDownloadManagerTest`，Robolectric + 系统 DownloadManager shadow，不联网）：

- 提交进系统 DownloadManager：MIME、标题、通知可见性、暂存目录（外部 `Download/book-downloads/`）；
- **身份去重**：同身份重复提交不新建任务；标题相同但来源不同 → 两条任务、两个落盘名；
- **进度与恢复**：新管理器实例（模拟进程重建）读回同一任务与进度；系统任务被清理后如实报失败；
- **大小三对照**：来源声明、系统报告、实际长度不一致 → `FAILED`，不入库；
- **HTML 登录页伪装 EPUB** → 拒绝，不入库（这是最现实的坏响应）；
- **声明格式与实际内容矛盾**（声明 EPUB 实为 PDF） → 拒绝；
- 失败任务可重试（旧系统任务与旧文件不删，重试复用同一落盘名，不产生重复暂存文件）；
- 记录损坏（坏 JSON）与偏好类型损坏 → 显式重试可恢复；
- `forget()` 只清应用侧记录，系统任务与文件保留；
- 公网明文 HTTP 拒绝、局域网/回环 HTTP 放行、非法 scheme 与无主机地址拒绝；
- **落盘命名**：标题不参与命名（同身份换书名同名）、换来源/换版本必不同名、非法字符与超长安全（含中文保留、长度上限）。

入库层（`DownloadedImportTest`，真 Room + 真文件系统）：

- 复制进私有 `filesDir/imported/` 且以「来源派生名」落盘，展示标题独立存在，不留 `.part` 半成品；
- 同身份重复入库幂等（复用记录、刷新标题与大小、不产生重复行）；
- **同名不同来源 = 两本书、两个文件、内容互不覆盖**（现有 `importSharedFile` 的痛点，下载路径绕开）；
- 不支持的扩展名拒绝且不残留文件。

## 一次失败与排除过程（记录在案）

全量测试第一次跑出 **24 项失败**，全部是 Compose UI 测试（HomeGroupFolders /
LibraryInteraction / QuickReadMenu / ReaderConfigCapsule / ReaderSheetCapsule / CoverFrameBorder），
异常统一为 `androidx.test.espresso.AppNotIdleException`。

定位：

1. 同一份生产代码在更早一次全量运行（09:50，`book-dl-fulltest.log`：62/433、0 失败）是绿的；
2. 该轮失败期间本机同时有多条 Gradle 任务/残留 java 进程在跑（我自己并发起的构建）；等待其退出后，
3. 单独重跑这 6 个 suite：**37 项全绿、0 个 AppNotIdleException、1m 42s 完成**（`book-dl-uiretry.log`）；
4. 机器安静后重跑全量：**437 项 0 失败**。

结论：该批失败是机器负载导致的 Compose idling 超时，与本轮代码无关。**教训：Compose UI 测试不要与其它 Gradle 构建并发跑。**

## 未实测

- **真机**：整条链路只在 Robolectric 的 DownloadManager shadow 上验证，没有在 Android 设备上真下载过一本书；
- **真实来源**：没有实现任何 `BookSource`，`acquire()` 未被真实目录调用过；
- **界面**：没有入口（没有来源就没有可浏览的目录），下载队列/进度/「已下载」状态没有 UI；
- **系统行为**：不同 ROM 的后台策略、DownloadManager 通知、存储权限、断网恢复；
- **大文件**：测试用文件是几十字节；数百 MB 漫画的体积、剩余空间不足、并发多任务未压测。

## 已知取舍与缺口

- 断点续传依赖系统 DownloadManager：**进程被杀不中断**，但失败后重试是重新提交，不保证字节续传；日志/校验按实际长度判断完整性。
- 主动校验只有「大小 + HTML/格式一致性」，没有哈希/签名（书籍来源没有可比官方哈希）。
- 任务记录存 SharedPreferences（单条，与更新器一致），**未新增 Room 表**——避免数据库迁移（迁移需单独审批）；代价是不能同时管理多条下载任务。多任务队列要等来源与界面一起设计。
- 入库成功后会 `remove()` 系统任务并删除外部暂存副本（书库私有副本是唯一事实来源）；取消/失败不删任何文件，用户文件与系统任务不做隐式清理。
- `BookSource` 目前只有 `acquire()`：目录浏览/搜索/分页等接口等第一个真实来源接入时再补，避免先造一套没人用的接口。

## 复现命令

```powershell
cd "F:\AI-hub\workroom\program\XY-reader"
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain          # 全量，安静机器上约 2 分钟
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --tests "com.xyreader.data.BookDownloadManagerTest"
& ./gradlew.bat :app:lintDebug --no-daemon --console=plain
```
