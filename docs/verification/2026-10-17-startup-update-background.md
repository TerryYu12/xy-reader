# 启动检查与后台更新验证记录

日期：2026-10-17（America/New_York）。GitHub `origin/main` 和本地 `main` 已同步至 `564137b8cbef67465bb1c5d8764f518ae036c685`，较同步前增加 7 个提交。实现位于本地分支 `codex/startup-update-background`，尚未提交或推送。原有三份未跟踪文档保留。

更新代码需要重新构建安装后生效；本轮未在 Master 实际使用的安装包入口验收，不能视为安装版已生效。

## 行为改动

每次应用进入 STARTED 前台生命周期后延迟 1.5 秒检查正式版，不再读取旧的 24 小时节流时间戳。Compose 重组不重新启动检查。旋屏等 Activity 重建也可能再次检查；安装器、系统设置返回前台同样会检查，但现有下载不会被检查结果替换。

APK 由 Android DownloadManager 下载至应用专属外部文件目录。弹窗可点击“后台下载”，应用内保留进度/安装入口；任务 ID 和 Release 信息持久化，重新创建宿主时查询恢复。未知长度显示不定进度，失败支持重试及浏览器兜底。已有活动任务不会被新发布覆盖。

下载完成后核对 Release 资产大小、系统报告长度和实际文件长度，再复制到既有 FileProvider 的私有缓存路径。安装必须点击触发，不在完成时主动拉起安装器。“稍后再说”保留已下载任务，稍后可重新打开安装入口。应用版本已达到下载版本时只清任务引用，不删除系统任务或文件。

## 已实测

执行命令：

```powershell
& ./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain
```

结果为 `BUILD SUCCESSFUL in 13m 21s`，日志位于 `outputs/update-full-verification.log`。

| 检查 | 结果 |
| --- | --- |
| 全量单元测试 XML | 61 个 suite，422 项，0 failures，0 errors，4 skipped；实际执行 418 项 |
| UpdateCheckLogicTest | 15 项，无失败、错误或跳过 |
| UpdateDownloadManagerTest | 10 项，无失败、错误或跳过 |
| UpdateDialogHostTest | 8 项，无失败、错误或跳过 |
| Lint XML | 21 warnings，0 errors；本轮改动文件没有 issue |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk`，40,838,598 bytes |
| APK SHA-256 | `98801E0756458D3BCC21C4E7233B3C86AF35539B4DF45318C975C2D55827512A` |
| Git 同步状态 | `git rev-list --left-right --count main...origin/main` 返回 `0 0` |
| 工作区 diff | `git diff --check` 无格式错误；仅提示 Windows 行尾转换 |
| ADB | `adb devices -l` 无设备 |

测试涵盖前后台往返重新请求、连续检查成功/失败后的重试、下载中收起弹窗、完成不自动启动安装器、暂缓后恢复安装入口、失败重试、管理器重建恢复、防重复提交、未知进度、文件缺失/截断、系统任务消失、损坏记录重试以及 HTTPS 限制。

首轮测试编译因测试中的 RuntimeEnvironment 泛型调用失败，已修正。第二轮 32 项中有 1 项目录断言失败：测试硬编码 Unix 路径，与 Windows Robolectric 目录不符；已改为比较系统返回目录的 canonicalFile。最终全量结果以上表为准。

## 未实测

- Master 实际安装包、设备上的启动入口与更新弹窗。
- 系统下载通知、断网恢复、应用进程回收后继续下载、不同 ROM 后台策略。
- 实际 GitHub APK 下载、未知来源授权返回与系统安装器升级流程。
- 画面布局及操作观感，待 Master 在安装版目检。

本轮只有 Debug 构建，没有 Release 打包、版本号递增、GitHub Release 发布或在线更新分发。Debug 包不能假定可覆盖现有正式签名安装包。

## 已知缺口

系统下载任务提交成功后，若 SharedPreferences commit 失败，可能留下没有应用内引用的系统任务；本轮保留明确错误，不删除系统任务或用户文件。坏 JSON 或偏好类型损坏后，可通过明确重试创建并保存新任务。

本轮做长度校验，没有增加 APK 哈希或签名的主动校验；系统安装器仍负责应用签名与升级检查。下载管理器本身的安全约束参见 [Android 官方说明](https://developer.android.com/privacy-and-security/risks/unsafe-download-manager)。强制停止应用及系统下载服务不可用等情况没有设备证据。

书库仅调研，未加入搜索或书籍/漫画下载功能。接入建议和来源证据见 [在线书库调研](../research/2026-10-17-library-catalogs.md)。

---

# 补测：Android 设备端验收（2026-10-17，MuMu Android 15）

本轮在 Android 15 模拟器（MuMu Nx，1080×2400，x86_64）上跑完了更新的完整链路。**注意：这是模拟器证据，不等于 Master 手机上安装版的验收**；通知样式、后台策略、安装器皮肤都与真机 ROM 有关。

## 测试方法

正式版 v0.5.9 已是当前最新 Release，没有「更新到新版」的真实场景。为不改动生产逻辑、不发布假 Release，本轮把本分支代码编译成 **versionName `0.5.8` / versionCode `32` 的正式签名包**（`keystore.properties` 存在 → 走 release 签名，与线上包同证书 `CN=XY Reader, O=TerryYu12`）。装上后应用自然认为线上 v0.5.9 是新版，于是走真实的 GitHub 检查、真实下载正式资产、真实安装升级。

- 测试包：`XY-READER-0.5.8-uitest.apk`（22,778,733 B，SHA-256 `6dffd43d2a8f59705d7c56904532338a0f7f87f2d85e0881f994689ae757c914`），仅存在于本机临时目录，**未入库、未发布**。
- 构建时为测试临时下调 `appVersionName` / `versionCode` 两行，构建完成后已 `git checkout` 还原；本文件记录时 `git status` 与本轮交接前一致（仅新增本目录截图）。
- 测试设备：模拟器原本装有 v0.5.5（release 签名）；升级链 0.5.5 → 0.5.8(测试包) → 0.5.9(线上包)，书库数据全程保留。

## 已实测（设备端）

| 检查 | 结果 |
| --- | --- |
| 冷启动自动检查 | 进入首页约 1.5s 后弹窗「发现新版本 v0.5.9 / 当前版本 v0.5.8」+ 更新说明 |
| 「立即更新」 | 走系统 DownloadManager；弹窗变「下载中… N%」，主按钮旁出现「后台下载」 |
| 「后台下载」 | 弹窗关闭，首页/书架/阅读页正常使用；首页底部常驻「更新下载 N%」入口 |
| 杀进程后重开 | 重开后入口恢复显示「更新下载 41%」，系统任务继续下载 |
| 下载产物完整性 | 设备成品 SHA-256 `836872a3c5118a4d11d72e8b482249f1a7a67237ea12e75ccad5d3eafe8dfb23`，与官方 `XY-READER-0.5.9.apk` **逐字节一致**（`cmp` 无差异，`apksigner` 验签同证书） |
| 完成后入口 | 变「安装更新 v0.5.9」；APK 已复制到私有缓存 `cacheDir/update/`（FileProvider `cache-path update/` 生效） |
| 未授权未知来源（targetSdk 34 默认态） | 点「安装更新」跳系统「安装未知应用」页；返回后弹窗提示「允许本应用安装未知应用后，返回此页点『继续安装』」并给出「继续安装」 |
| 授权后安装 | 系统安装器「要更新此应用吗？→ 更新」→「已安装应用」 |
| 升级结果 | versionName `0.5.9` / versionCode `33`；书库 3 本、分组、阅读进度全部保留 |
| 版本达标的再次启动 | 更新弹窗与入口均不再出现（任务引用已清） |
| 二次安装（权限已授予） | 直接进系统安装器，不再经设置页 |
| 崩溃 | logcat 无应用崩溃（`uiautomator` 自身的 Shutdown thread SIGSEGV 与本应用无关） |

截图：`screenshots/2026-10-17-update/01-发现新版本-v0.5.9.png`、`02-下载中-可后台下载.png`、`03-首页更新入口-后台下载中.png`、`04-阅读页仍显示更新入口.png`。

## 设备端复现的缺陷（未修复）

**更新包下载过程中打开书库书籍，阅读页渲染失败并停在「第 1 页加载失败」，退出重进才恢复。**

| 条件 | 结果 |
| --- | --- |
| 更新下载进行中（39%）打开 CBZ 与 PDF 书 | 2/2 失败（`第 1 页加载失败`） |
| 下载完成/无下载时打开同一本书 | 3/3 正常（`第 1 页`） |
| 无更新下载，仅持续 600MB 外部存储写入（`dd` 直写 `/storage/emulated/0/Download/`） | 正常 —— 排除「单纯重 I/O 就会失败」 |

- 触发条件是**本应用的更新下载在跑**，不是通用 I/O 压测；影响面正好是本功能主打的「后台下载时继续阅读」。
- 根因未定：`ReaderViewModel.kt:579` 的 `catch (_: Exception)` 静默吞掉异常且不写 `AppLog`，logcat 无任何线索。**建议先在该 catch 内加一行 `AppLog.w`（并在 `PageUi.Failed` 时提供重试入口），再复现定位真实异常**；本轮未改阅读器代码。
- 复现方法：装 `XY-READER-0.5.8-uitest.apk`（或任意低于线上版本的正式签名包）→ 启动等弹窗 → 「立即更新」→「后台下载」→ 立刻打开一本书的「开始阅读」。

## 未实测（仍待 Master）

- **Master 手机上安装版的真实验收**（本机只有模拟器）：通知、后台策略、系统安装器、未知来源授权返回。
- 真机上「下载中继续阅读」的点开链路（模拟器已复现缺陷，真机行为待确认）。
- 强制停止应用、系统下载服务被禁用、断网恢复等边界。
- 主动 hash/签名校验（本轮实现只校验长度，设备端确认安装器仍负责签名校验）。

## 环境侧观察（非本项目缺陷）

测试期间本机 `api.github.com` **间歇性连接失败**（同一分钟内 `curl` 6 次中 4 次返回 `000`，而 `github.com`、`objects.githubusercontent.com` 正常）；模拟器上首次自动检查因此报 `javax.net.ssl.SSLHandshakeException: connection closed`，属本机代理链路问题。含义：检查失败时应用是静默降级（只写日志），GitHub API 不可达的用户不会看到任何提示。
