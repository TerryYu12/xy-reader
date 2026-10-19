# 书源「整本抓取」前台服务 验证记录

日期：2026-10-18。分支：`codex/startup-update-background`。基线：工作区含大量未提交改动（本任务同批）。
范围：把书源「整本抓取 + 入库」从前台协程迁到 Android **前台服务**，带常驻进度通知、可取消；
UI 改为经服务触发并观察状态。

## 结论

代码、单测、release 打包（R8）均通过；**没有任何真机/模拟器验证**（本机无 adb，见「未实测」）。
因此本阶段交付的是「自动化可验证的编排 + 已合并的 manifest 声明」，不能称为「后台存活已被真机验证」。

## 改动文件

| 文件 | 变更 |
| --- | --- |
| `app/src/main/java/com/xyreader/booksource/BookFetchService.kt` | **新增**：前台服务（进入前台 → 调 `LegadoBookDownloader.download` → 推送通知/状态 → 收尾；`ACTION_CANCEL` 取消协程） |
| `app/src/main/java/com/xyreader/booksource/BookFetchState.kt` | **新增**：进程级状态单例（`StateFlow`，键 `bookUrl`；阶段 RUNNING/DONE/FAILED/CANCELLED） |
| `app/src/main/java/com/xyreader/booksource/LegadoBookDownloader.kt` | `open class` + `download` 改 `open`（供测试替身）；`catch (CancellationException) throw`（取消向上传播，不再被当成失败吞掉）。**API 签名与失败语义不变** |
| `app/src/main/AndroidManifest.xml` | 加 3 条权限 + `<service ... foregroundServiceType="dataSync">` |
| `app/src/main/java/com/xyreader/ui/BookSourceSearchScreen.kt` | 不再直接 `downloader.download()`；改为 `BookFetchService.start/cancel` + 观察 `BookFetchState`；行内「取消」按钮；Android 13+ 请求 `POST_NOTIFICATIONS`（拒绝也放行抓取） |
| `app/src/test/java/com/xyreader/booksource/BookFetchServiceTest.kt` | **新增**：服务编排单测（假下载器，4 项） |
| `app/src/test/java/com/xyreader/booksource/BookFetchServiceManifestTest.kt` | **新增**：合并后 manifest 声明校验（2 项） |
| `app/src/test/java/com/xyreader/booksource/LegadoBookDownloaderTest.kt` | 加 1 项：取消 → 不入库、不留文件 |

未引入任何新依赖（无 WorkManager），未改 `LegadoBookDownloader` 的对外 API。

## 前台服务类型与权限依据

- `targetSdk = 34`（`compileSdk = 36`，见 `app/build.gradle.kts`）。
- Android 14（API 34）起：前台服务**必须**声明 `foregroundServiceType`，并为该类型声明配套的
  `FOREGROUND_SERVICE_<类型>` 权限，否则 `startForeground` 抛异常（`SecurityException`/`MissingForegroundServiceTypeException`）。
- 本服务选 **`dataSync`**：整本抓取是一次可能持续数分钟的数据同步（目录 + 逐章正文），不属于
  「短任务」。`shortService` 语义是数十秒级的抢占式短任务，且不能与其他类型共存，与本场景不符。
- 因此声明并使用：`android.permission.FOREGROUND_SERVICE`、`android.permission.FOREGROUND_SERVICE_DATA_SYNC`。
  服务代码在 API 29+ 调 `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`，
  与 manifest 的 `foregroundServiceType="dataSync"` 一致。
- `POST_NOTIFICATIONS`（Android 13+）是**运行时**权限：UI 在发起抓取时请求；被拒不影响抓取
  （服务照常 `startForeground`，只是通知不可见）。服务内部不请求该权限。
- 启动时机：只在用户于页面点击「抓取整本」时由**前台中的应用**调用 `startForegroundService`，
  不涉及 Android 12+ 的后台启动前台服务限制。

合并后的 manifest（release，实测）：

```
app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
  <service android:name="com.xyreader.booksource.BookFetchService"
           android:exported="false"
           android:foregroundServiceType="dataSync" />
```

## 已实测

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 定向单测（booksource + ui） | `./gradlew.bat :app:testDebugUnitTest --tests "com.xyreader.booksource.*" --tests "com.xyreader.ui.*" --no-daemon --console=plain` | `BUILD SUCCESSFUL in 6m 5s`；**21 suite、133 项、0 失败、0 错误、0 skipped** |
| 新增：服务编排 | `BookFetchServiceTest`（Robolectric + 假下载器） | 4 项，0 失败 |
| 新增：manifest 声明 | `BookFetchServiceManifestTest`（Robolectric 读 AGP 合并产物） | 2 项，0 失败 |
| 取消语义（真实下载器） | `LegadoBookDownloaderTest`（含新增取消项） | 5 项，0 失败 |
| 全量单测 | `./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain` | `BUILD SUCCESSFUL in 2m 33s`；**74 suite、512 项、0 失败、0 错误、4 skipped** |
| release 打包（R8/资源收缩） | `./gradlew.bat :app:assembleRelease --no-daemon --console=plain` | `BUILD SUCCESSFUL in 14m 28s`；产出 `app/build/outputs/apk/release/app-release.apk`（23.4 MB）；**R8 无 missing_rules / 无新增告警** |
| 合并后 manifest | 解析 `merged_manifest/release/.../AndroidManifest.xml` | 3 条权限 + 服务声明 + `foregroundServiceType="dataSync"` 均在（见上） |

日志：`F:\AI-hub\agent\Hermes\cache\scratch\xyr-fgs\`
`test-targeted-2.log`（定向）、`test-full.log`（全量）、`assemble-release.log`（release）。

### 覆盖的行为

服务编排（`BookFetchServiceTest`，Robolectric，不联网）：

- 启动 → 进入前台 → 调下载器 → 状态 **DONE** 且回填 `bookId`；中途每次 `onProgress` 都被写进
  `BookFetchState`（断言「服务确实把第 n/m 章进度推给了状态」，而非只是调了回调）；
- 下载失败 → 状态 **FAILED**、`bookId == null`、带原始错误信息；
- 运行中点「取消」→ 状态 **CANCELLED**；
- 同一本书重复启动被忽略（不新建任务、不重置进度）。

取消语义（`LegadoBookDownloaderTest`，真实下载器 + 假 fetcher）：

- 第二章卡住时取消协程 → **不入库**（importer 零调用）、**临时目录无残留文件**。

manifest（`BookFetchServiceManifestTest`，读合并产物）：

- 三条权限齐全；`BookFetchService` 已声明、`exported=false`、`foregroundServiceType == dataSync`。

## 未实测

- **真机/模拟器后台存活**：本机**无 adb**（`adb devices` → `command not found`），没有连接任何设备，
  未验证「App 退到后台后抓取继续」、通知展示、通知栏「取消」按钮点击、Android 12+ 后台启动限制的真实触发。
- **Android 13+ 通知权限弹窗**与「拒绝后仍能抓取」的真机行为（仅有代码路径，无运行时验证）。
- **Android 14/15 对 dataSync 的实际限制**：API 34 上 `dataSync` 前台服务可用；但 Android 15（API 35）
  对 `dataSync` 引入**每 24 小时累计约 6 小时**上限，超时系统会回调 `onTimeout`。本应用 **targetSdk = 34**，
  当前不触发该上限；抓一本（分钟级）远低于阈值。若将来把 targetSdk 提到 35，需复核并实现 `onTimeout`。
- 与真实书源联调的整本抓取（本任务不依赖网络，单测全部用假 fetcher）。

## 风险 / 已知缺口

1. **后台存活未经验证**：所有保证来自代码与单测，真机后台回收行为未测。这是本阶段最大缺口。
2. **进程被杀**：若系统在抓取中直接杀进程（低内存等），`START_NOT_STICKY` 不重启；已抓内容不会入库
   （仍满足「不留半本书」），但用户需重新发起。属于与既有下载语义一致的取舍。
3. **服务被系统回收后的状态残留**：`BookFetchState` 是进程内单例，进程被杀即清空；页面在重启后看不到
   「正在抓取」，与后台服务真实状态可能短暂不一致（服务已被回收 → 实际没在抓）。
4. **通知 id 用 `bookUrl.hashCode()`**：理论上有碰撞可能（极小），碰撞时两本书共用一个通知，属可接受
   的已知取舍。
5. **`onTimeout` 未实现**：见「未实测」第 3 条，仅在 targetSdk ≥ 35 时才需要。
6. 未跑 lint（本任务未纳入 lint 验证；如需可补 `:app:lintDebug`）。

## 文档路径

本文件：`docs/verification/2026-10-18-book-fetch-foreground-service.md`。
