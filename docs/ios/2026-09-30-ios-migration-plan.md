# XY reader iOS 版技术方案（2026-09-30）

> 面向读者：XY reader 项目作者（有 Android 开发经验）。
> 结论先行：**推荐路线 A —— 用 Kotlin Multiplatform + Compose Multiplatform 共享代码，iOS 侧只写少量 Swift 胶水层**。MVP 预估 **8–11 人周**。
> ⚠️ **本文档的全部技术结论基于静态源码分析与公开资料调研，未在 iOS 上编译或运行验证过。** 当前开发机为 Windows 10，无 macOS、无 Xcode，任何 iOS 产物（framework / .app / 模拟器运行）都无法在现有环境下构建或验证。详见第 4 章。

---

## 0. TL;DR

| 项 | 结论 |
|---|---|
| 推荐路线 | **A：Compose Multiplatform 共享移植**（逻辑 + UI 共享，Swift 只做胶水） |
| 预估代码复用率 | **逻辑层 70–85%**（core/reader 的纯 Kotlin 部分几乎零改动），**UI 层 60–75%**（Compose 代码可直接编译到 iOS） |
| MVP 工作量 | **约 8–11 人周**（单人全职，含 iOS 工程搭建与真机联调） |
| 最大阻塞 | **没有 Mac**：iOS 的编译、调试、签名、真机验证全部只能在 macOS + Xcode 上进行 |
| 第二大阻塞 | **压缩包解析库是 JVM-only**（commons-compress / junrar），iOS 侧必须换实现 |
| 第三大阻塞 | 数据层（Room 2.6.1 非 KMP）、网络层（OkHttp）、图加载（Coil 2）、导航（Jetpack Navigation）都要换 KMP 对应物 |

---

## 1. 项目现状盘点

### 1.1 工程结构与规模

- 单一 Gradle 模块 `:app`（`settings.gradle.kts` 只 `include(":app")`），根工程名 `XY-READER`。
- 源文件 **62 个 .kt**，约 **16,600 行 Kotlin**（`app/src/main/java`）。
- 包结构（`app/src/main/java/com/xyreader/`）：

| 包 | 内容 | 平台耦合度 |
|---|---|---|
| `core/` | `Book`/`BookFormat`/`PageSource`/`ArchiveFactory`/`ReaderPrefs`/`QuickRead`/`NovelFonts`/`Repository` | 低（但 `PageSource` 返回 Android `ImageBitmap`） |
| `archive/` | ZIP/CBZ、7Z、RAR、TAR、PDF、MOBI、EPUB/TXT 的 `PageSource` 实现；`HttpRangeChannel`（远程流式 Range）；`NovelTextExtractor`（TXT/EPUB/MOBI 文本抽取） | 高（依赖 commons-compress / junrar / `android.util.Xml` / `BitmapFactory` / SAF） |
| `data/` | Room（`ArkDatabase`/各 Dao）、`LibraryScanner`（本地扫描）、`WebDavClient`/`WebDavScanner`、`GoogleDriveClient`/`GoogleDriveAuth`/`GoogleDriveScanner`、`ReaderPrefsStore`、`LibraryLayoutStore` | 高（Room + OkHttp + `DocumentFile` + `ContentResolver` + `LocalServerSocket`） |
| `reader/` | `ReaderScreen`/`ReaderViewModel`/`PageZoom` | 中（Compose UI + Android 手势） |
| `ui/` | 主页/书架/书详情/仓库管理/设置/隐私/分组等 Compose 页面 + `Navigation.kt` | 中（Compose + `ActivityResultContracts.OpenDocumentTree`） |
| 顶层 | `MainActivity`、`ArkApp`、`SharedIntake`（接收 `content://`/`file://` 的分享与打开） | 高（Activity / Intent） |

**关键观察**：项目已经做了不错的抽象——`PageSource`（页来源接口）、`ArchiveFactory`（按格式路由）、`NovelTextExtractor`（不引第三方 XML 库，用 Android 自带 `XmlPullParser`）——这些**纯逻辑部分本身是可移植的**，被卡住的主要是「具体实现里调用了 Android/JVM API」这一层。

### 1.2 关键依赖与 iOS/KMP 可用性逐项分析

| 依赖（当前版本） | 用途 | iOS 可用性 | 结论 / 替代方案 |
|---|---|---|---|
| **Kotlin 2.0.21 / Compose BOM 2024.12.01** | 语言 + UI | ✅ KMP 原生支持 | 升级到 **Kotlin 2.2+**、**Compose Multiplatform 1.12.x**（见下） |
| **`org.apache.commons:commons-compress:1.27.1`** | ZIP/7Z/TAR 解析 | ❌ **纯 JVM 库，无法编到 Kotlin/Native** | iOS 侧换实现（见 1.3） |
| **`com.github.junrar:junrar:7.5.5`** | RAR 解析 | ❌ **纯 JVM 库**（且仅支持 RAR4，未跟上 RAR5） | iOS 侧换实现（见 1.3） |
| **`androidx.room:room-*:2.6.1`** | 本地数据库 | ⚠️ Room 自 **2.7.0** 起成为 KMP 库（支持 Android/iOS/JVM/Mac/Linux），2.6.1 不行 | 升到 **Room 2.7+/2.8**，配 KSP + SQLite 驱动；或换 **SQLDelight**（更稳的 KMP 选择）。注：Room 3.0 alpha（2026-03）已转向纯 KMP 但仍在早期 |
| **`com.squareup.okhttp3:okhttp:4.12.0`** | WebDAV / Google Drive HTTP | ❌ OkHttp 是 JVM/Android 库 | 换 **Ktor 3.x**：`ktor-client-core` 在 commonMain，Android 用 `ktor-client-okhttp`，**iOS 用 `ktor-client-darwin`**（底层 NSURLSession） |
| **`io.coil-kt:coil-compose:2.7.0`** | 封面图加载 | ⚠️ Coil 2 非 KMP；**Coil 3**（2024-11 起，坐标为 `io.coil-kt.coil3`）是 KMP 库 | 升到 **Coil 3.x**：`coil-compose` + `coil-network-ktor3`（配 Ktor 引擎）放 commonMain；纯本地图片可不需要网络插件 |
| **`androidx.datastore:datastore-preferences:1.1.1`** | 阅读偏好等持久化 | ✅ **DataStore 自 1.1.0 起支持 KMP**（Preferences 支持；Proto 视情况） | 直接用 **`datastore-preferences-core` + Okio 存储**，iOS 路径取 `NSDocumentDirectory` |
| **`androidx.navigation:navigation-compose:2.8.5`** | 页面导航 | ✅ JetBrains 维护 KMP 版：`org.jetbrains.androidx.navigation:navigation-compose`（2.9.x / 2.10.x） | commonMain 换成 org.jetbrains 的坐标；iOS 自带边缘返回手势 |
| **Compose（UI 框架）** | 全部界面 | ✅ **Compose Multiplatform iOS 自 1.8.0（2025-05）起标记 Stable**，当前最新 **1.12.1（2026-09-22）** | 现有 Compose 页面大部分可直接编译到 iOS |
| **`androidx.activity:activity-compose`、`androidx.core:core-ktx`、`androidx.documentfile`** | Activity/系统 API/SAF | ❌ Android 专有 | iOS 侧由 Swift 胶水层 + FileKit 等 KMP 库替代 |
| **`androidx.lifecycle:lifecycle-viewmodel-compose`** | ViewModel | ✅ JetBrains 提供 KMP 版 `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose`（2.9.x+） | 换坐标即可 |
| **`PdfRenderer`（Android 内建）** | PDF 渲染 | ❌ Android 专有 | iOS 用 **PDFKit**（Apple 原生，性能好）；PDF 属阶段 3 |
| **`BitmapFactory` / `ImageBitmap`** | 图片解码 | ⚠️ `ImageBitmap` 在 CMP 里有公共 API，但字节解码要走平台实现 | iOS 用 `skia`/`Image` 解码（CMP 已封装）或 `UIImage` |

### 1.3 压缩包解析：iOS 上的替代路径（**本方案最大的技术风险点**）

当前 Android 侧：ZIP/7Z/TAR → commons-compress，RAR → junrar（JVM-only）。iOS（Kotlin/Native）没有等价的官方库，需要重新选型。可选路径：

**路径 ①：libarchive（C 库）+ cinterop —— 推荐用于「一条依赖通吃 ZIP/7Z/TAR/RAR」**
- libarchive 原生支持读 ZIP、7-Zip、TAR、RAR/RAR5，并且是**流式**的（契合本项目已有的「Range 流式读取」设计）。
- 集成方式：把 libarchive 源码编成 iOS 静态库（或其 Swift Package / XADMaster 封装），再用 Kotlin/Native 的 **cinterop**（写 `.def` 文件声明头文件/静态库路径）暴露给 Kotlin；或把归档访问整体放在 Swift 侧，通过 `expect/actual` 接口回调。
- 参考：Android 上的 `me.zhanghai.android.libarchive` 就是 JNI 包 libarchive 的同思路实现；Kotlin Slack 上也有开发者确认「KMP 下想读 zip/rar，libarchive + 自写绑定」是主流方案。
- 代价：需要交叉编译 C 库、维护 cinterop 绑定、处理错误码映射。**这是本项目 iOS 侧最重的一块工作量。**

**路径 ②：纯 Swift 库 + Swift 胶水层**
- **ZIP**：`ZIPFoundation`（用得最广）或 **SWCompression**（一个 Swift 框架，原生支持 **ZIP / TAR / 7-Zip 的读取**，且有 LZMA/LZMA2/BZip2/LZ4 解压）。
- **7Z**：`SWCompression/SevenZip` 或 LZMA SDK（`LzmaSDK-ObjC`）。
- **RAR**：**`UnrarKit`**（`abbeycode/UnrarKit`，ObjC 封装 UnRAR 5.8.1，支持 RAR4/RAR5、密码、逐块读取）——iOS 上解 RAR 的事实标准；另可考虑 `SARUnArchiveANY`（整合 zip/rar/7z）或 libarchive 的 RAR reader。
- 代价：多语言（Swift/ObjC+C++）依赖，且这些是**Swift/ObjC 库**——Kotlin/Native 的 cinterop 支持 Objective-C，但**纯 Swift pod 不被 CocoaPods 集成支持**，需要中间 ObjC 桥接或把归档层留在 Swift 侧。

**路径 ③：混合**——ZIP/7Z/TAR 走 libarchive（path ①），RAR 单独接 UnrarKit（path ②）。适合「不想全押 libarchive 的 RAR reader 稳定性」时。

> 建议：**MVP 先做 libarchive（路径 ①）单栈覆盖 ZIP/7Z/TAR，RAR 视联调结果决定是走 libarchive 还是 UnrarKit**。若 RAR 在 iOS 上风险过高，MVP 可先只保证 ZIP/7Z/TAR + TXT/EPUB，RAR 延后（Android 端已有 junrar，iOS 缺 RAR 对 MVP 影响可控）。
> 另有一个轻量 KMP 选项：`kmp-zip`（henrik242/kmp-zip，Kotlin 多平台 ZIP/GZIP，支持 JVM/iOS/macOS/Linux/Windows/Wasm），可只用于纯 ZIP/CBZ 场景，但**不支持 7Z/RAR**。

### 1.4 文件访问：Android SAF ↔ iOS 文档选择器

| Android（现状） | iOS 对应 |
|---|---|
| `ActivityResultContracts.OpenDocumentTree()` 选目录 | `UIDocumentPickerViewController`（iOS 13+ 支持选目录） |
| `contentResolver.takePersistableUriPermission(...)` 持久授权 | **security-scoped bookmark**：选完文件/目录后 `startAccessingSecurityScopedResource()`，用 `bookmarkData(...)` 存书签，下次启动 `resolve` 后重新 start/stop |
| `content://` URI 标识 | `NSURL`（**不能直接存路径字符串**，路径会失效，必须存书签） |
| `DocumentFile.fromTreeUri(...)` 遍历目录 | `FileManager.enumerator(at:)` 或 `URL` 遍历（返回的 URL 同样是 security-scoped） |
| `ContentResolver.openFileDescriptor` 随机读 | `FileHandle`（支持 seek/`read(upToCount:)`，可做流式） |
| `SharedIntake` 接收 `ACTION_VIEW` / 分享 | iOS **Document Types + `application(_:open:)` / Share Extension** |

**KMP 现成方案：`FileKit`（vinceglb/FileKit）** —— 文件/目录选择器 + 保存对话框，Android 用 SAF、**iOS 用 `UIDocumentPickerViewController` / `PHPickerViewController`**，并内置 **`BookmarkData`**（跨平台持久访问：iOS 走 Foundation 书签 + security-scoped URL 语义），可直接替代本项目里 SAF 那套 `DocumentFile` + `takePersistableUriPermission` 的逻辑。

### 1.5 远程仓库：WebDAV 与 Google Drive

- **WebDAV（现状）**：`WebDavClient` 用 OkHttp 发 `PROPFIND`（Depth:1，解析 207 multistatus）+ `Authorization: Basic` + Range 流式读。
  - iOS 迁移：**协议层可 100% 复用**，只需把 HTTP 客户端从 OkHttp 换成 **Ktor**（`ktor-client-darwin`）。`HttpRangeChannel`（Range 流式随机读）的逻辑保持不变，换掉底层 client 即可。
- **Google Drive（现状）**：`GoogleDriveAuth` 不引 Google SDK，自己走 OAuth2 **浏览器 loopback 授权流**（`http://127.0.0.1:<port>` 回调 + `LocalServerSocket`），换 `refresh_token`，scope 为 `drive.readonly`。
  - **iOS 上 loopback 回调不可行**（无法在本机起可被浏览器回调的监听端口 / 体验差）。iOS 标准做法是 **`ASWebAuthenticationSession`**：用自定义 URL scheme（如 `xyreader://oauth2redirect`）或反向域名回调，`ASWebAuthenticationSession` 会自动捕获回调 URL 并返回授权码，再 POST token 端点换 `refresh_token`（这段 POST 逻辑可直接复用）。
  - 需在 Xcode 配置 URL Types（URL scheme）与 `Info.plist`；生产上架后 Google 要求应用验证（sensitive scope）。
  - 备选：iOS 自带 Google Sign-In SDK（但会引入 Google 依赖，与本项目「不引官方 SDK」的取向不符）。

---

## 2. 两条路线对比

### 路线 A：Compose Multiplatform 共享代码移植（推荐）

把 `core/`、`reader/`、`archive/` 的纯逻辑、`data/` 的数据模型与仓储、以及大部分 `ui/` Compose 页面放进 KMP 的 `commonMain`，Android 与 iOS 共享同一套代码；平台相关部分（文件访问、归档底层、HTTP 引擎、图片解码）用 `expect/actual` + Swift 胶水实现。

- **可行性：高。** Compose Multiplatform iOS 已 Stable（1.8.0 起，2025-05；最新 1.12.1）。Jetpack 的 Room/DataStore/Navigation/ViewModel 均有官方或 JetBrains 的 KMP 版本可用。
- **代码复用率估算**：
  - 逻辑层（`core`/`reader` 的纯逻辑、格式识别、`NovelTextExtractor` 的 TXT/EPUB 解析与分页、WebDAV 协议解析）：**70–85%**。
  - UI 层（Compose 页面、主题、手势）：**60–75%**（个别依赖 Android 专有 API 的交互要拆 `expect/actual`）。
  - 归档底层与文件访问：**0–20%**（几乎要重写，是主要成本）。
- **主要阻塞点**：
  1. 归档解析库整体替换（libarchive/SWCompression/UnrarKit）+ cinterop 绑定；
  2. 数据层要从 Room 2.6.1 迁到 Room 2.7+（KSP + SQLite 驱动）或 SQLDelight；
  3. 库升级连带影响：Compose BOM → CMP 1.12、Coil 2 → Coil 3、OkHttp → Ktor、Navigation 换 JetBrains 坐标；
  4. `PageSource` 接口里的 `ImageBitmap` 与 Android 特有的 `BitmapFactory`/`XmlPullParser` 要抽象成 `expect/actual`；
  5. `LocalServerSocket` OAuth 流程在 iOS 要改成 `ASWebAuthenticationSession`。
- **优点**：一套业务逻辑两处复用，长期维护成本最低；新增格式/修 bug 两端同时受益；Android 端的 Compose 经验直接迁移。
- **缺点**：需要先做一次不小的「KMP 工程化改造」（拆 `shared` + `androidApp` + `iosApp`）；iOS 上 Compose 渲染与系统控件（返回手势、滚动惯性、字体、无障碍 VoiceOver）虽已 Stable 但与纯原生仍有细微差距；Windows 上开发 iOS 部分体验差（无 IDE 级 iOS 调试）。

### 路线 B：SwiftUI 原生重写

iOS 侧从零用 Swift/SwiftUI 写，归档用 ZIPFoundation/SWCompression/UnrarKit，数据库用 Core Data 或 SQLite.swift，网络用 URLSession，图片用 AsyncImage/SDWebImage。

- **可行性：高（技术上），但成本高。**
- **代码复用率：≈ 0**（Kotlin 代码除少量算法可照抄重写外，几乎无法复用；6 万+ 字符的业务逻辑全部重来）。
- **主要阻塞点**：全部逻辑与 UI 重实现；两套代码库长期并行维护；Android 端后续改动无法自动同步到 iOS。
- **优点**：iOS 原生体验最好（控件、手势、动画、无障碍、Metal 图片性能）；无 cinterop/跨语言调试之痛；iOS 端可独立演进。
- **缺点**：重复劳动最多，MVP 更慢；双份维护。

### 路线对比表

| 维度 | A：Compose Multiplatform | B：SwiftUI 原生 |
|---|---|---|
| 逻辑复用 | 70–85% | ~0% |
| UI 复用 | 60–75% | 0% |
| MVP 工期（单人） | **约 8–11 人周** | 约 12–16 人周 |
| iOS 原生体验 | 良好（Stable，细节略差） | 最佳 |
| 长期维护成本 | 低（单套逻辑） | 高（双套） |
| 技术风险 | 归档 cinterop + 工具链 | 低（都是成熟方案） |
| 对 Windows 开发机的友好度 | 低（很多 iOS 工作必须上 Mac） | 更低（全在 Xcode） |

### 明确推荐

> **采用路线 A（Compose Multiplatform 共享移植）**，理由是：项目 62 个文件、约 16.6k 行 Kotlin 里，真正与 Android 强耦合的只是「归档底层 + 文件访问 + 少量系统 API」，而格式识别、文本抽取、分页、WebDAV 协议、大部分 Compose UI 都是可复用的纯逻辑；CMP iOS 已 Stable，Room/DataStore/Navigation/ViewModel 都有 KMP 版本。**MVP 只用先做「本地导入 + 压缩包阅读 + 小说阅读」，正是复用率最高、最不依赖 iOS 原生能力的那一块**，与「MVP 先行」的方向完全吻合。路线 B 在 MVP 阶段纯属重复劳动，除非作者把 iOS 原生体验当作第一优先级、且愿意接受双份维护，才选 B。

---

## 3. 分阶段实施计划

> 前提：先把工程改造成 KMP 结构（`shared` 共享模块 + `composeApp`/`androidApp` + `iosApp`），这一步在两阶段里都摊掉。
> 工作量单位「人周」= 单人全职一周；估算含设计、编码、联调与缓冲。

### 阶段 1：MVP —— 本地导入 + 压缩包阅读 + 小说阅读

**功能清单**
- 本地导入：从「文件」App / 分享进入导入书籍（文件与目录），授权持久保存。
- 格式识别（`BookFormat` 复用）。
- 压缩包阅读：**ZIP/CBZ、7Z/CB7、TAR/CBT 的图片分页**（RAR 视风险决定是否进入 MVP）、EPUB（文本版与图片版回退）、**TXT 小说阅读**。
- 封面（首图 / 约定封面文件）、书架列表、书详情。
- 基础阅读器 UI：分页翻页、缩放、阅读偏好（字号/间距/背景/字体）、进度记忆。
- （可选顺带）MOBI/AZW3 文本管线——`NovelTextExtractor` 已是纯 Kotlin，成本低。

**技术要点**
- 拆 KMP：`core`/`reader`/`ui` 大部分迁入 `commonMain`；`archive` 的 `PageSource` 接口保留，新增 `iosMain` 实现。
- 归档底层：libarchive（cinterop）优先；ZIP 可先用 `kmp-zip` 或 SWCompression 过渡。
- 文件访问：FileKit（选择器 + BookmarkData）。
- DB：Room 2.7+ KMP（或 SQLDelight）承载现有 `Book Entity`/各 Dao。
- 图片解码：`expect/actual`，Android 用 `BitmapFactory`，iOS 用 Skia/`UIImage`。
- 导航：`org.jetbrains.androidx.navigation:navigation-compose`。
- iOS 工程：Xcode 项目 + `ComposeUIViewController` 承载 Compose 根；`Info.plist` 声明支持的 `UTType`（`public.archive` / `public.zip-archive` / `public.plain-text` / `public.epub` 等）+ Document Types。

**风险**
- 归档 cinterop 是硬骨头（编 C 库、写 `.def`、错误处理）——**最高风险**。
- 大压缩包内存/性能（Android 端已有 Range 流式设计，需在 iOS 复刻同样的分页懒加载）。
- 首次 KMP 工程改造可能踩到依赖版本对齐（CMP ↔ Kotlin ↔ KSP）的坑。

**人周估算：8–11 人周**（明细：KMP 改造 1–1.5；归档层 2–3；小说管线移植 0.5–1；文件访问 0.5–1；DB 迁移 1；UI 适配 1.5–2；iOS 工程 + 真机联调 1–1.5）。

### 阶段 2：远程仓库（WebDAV + Google Drive）

**功能清单**
- WebDAV 仓库配置、PROPFIND 列目录、连通性测试、Range 流式阅读、整包下载缓存。
- Google Drive OAuth 授权、文件列举、流式读取。
- 远程书架与本地书架合并、缓存与失效策略。

**技术要点**
- HTTP 换 **Ktor**（`ktor-client-darwin` 走 NSURLSession）；`WebDavClient` 的协议逻辑基本原样复用。
- OAuth：`ASWebAuthenticationSession` + 自定义 URL scheme 回调，token 交换逻辑复用；`refresh_token` 安全存储改用 Keychain（替代 Android 的加密偏好）。
- 远程流式读：libarchive 支持流式，是关键——需验证 iOS 侧能边下边解。

**风险**
- Google Drive OAuth 在 iOS 的应用验证与回调 scheme 配置；上架 Google 政策。
- 远程大文件流式解压的性能与断点续传。

**人周估算：3–4.5 人周。**

### 阶段 3：完整功能对等

**功能清单**
- PDF 阅读（iOS 用 **PDFKit**）、MOBI/AZW3 完整支持、RAR 补齐（若阶段 1 未做）。
- 分组管理、书架布局定制、快捷阅读菜单、隐私页、多仓库、Google Drive 深度集成。
- iOS 专有：分享扩展、AirDrop/文件 App 集成、iCloud Drive、Handoff（可选）、动态字体/深色模式适配。

**技术要点**
- PDF：`PDFKit`（`PDFView`/`PDFDocument`）替换 Android `PdfRenderer`。
- 上架准备：代码签名、App Store Connect、隐私清单（Privacy Manifest）、Google Drive 数据使用政策。

**风险**
- App Store 审核（尤其 Google Drive 权限说明、文件访问权限）。
- 两端行为差异的回归测试。

**人周估算：4–6 人周。**

---

## 4. 环境与验证限制（**必须如实对待**）

> **当前开发机为 Windows 10（无 macOS、无 Xcode）。本文档所述的全部 iOS 构建、运行、调试、真机验证工作，均无法在当前环境完成。**

**硬性限制**
- Kotlin/Native 的 **iOS target 只能在 macOS 上编译**（需要 Xcode 工具链、Apple SDK）。Windows 上可以写 Kotlin `iosMain` 代码，但**无法编译出 iOS framework**。
- **模拟器/真机运行**：必须 macOS + Xcode +（真机另需 Apple 开发者账号与签名）。
- **归档 cinterop**：需要交叉编译 C 库（libarchive）与生成绑定，实质在 macOS 上完成。
- **Swift 胶水层、`ASWebAuthenticationSession`、URL scheme、`Info.plist`、签名**：全部在 Xcode 里做。
- App Store 上架：必须 macOS + Xcode。

**可在 Windows 上完成的工作（代码与文档为主）**
- KMP 工程结构改造的**配置草稿**（`build.gradle.kts`、`commonMain` 迁移、`expect` 声明）。
- 纯逻辑代码迁移到 `commonMain`：格式识别、`NovelTextExtractor`（TXT/EPUB）、分页算法、WebDAV 协议解析、数据模型与 Dao 接口——**可在 Windows 上编译 Android target 来验证逻辑正确性**（Android 产物仍能构建）。
- 编写 `iosMain` 的 `actual` 实现（能写、能提交，但**不能编译验证**）。
- iOS 侧文档、`Info.plist` 草稿、URL scheme 设计、Swift 胶水层代码草稿。
- 通过 CI（GitHub Actions 的 macOS runner）做 iOS 编译验证——这是**在没有 Mac 的机器上唯一可自动化验证 iOS 编译**的路径，但只能验证「能编过」，不能真机调试。

**必须在 macOS 上完成的工作**
- 一切 iOS 编译（framework / .app / archive）、归档 cinterop 交叉编译、Swift 胶水层接入、Xcode 工程配置与签名、模拟器/真机运行与调试、性能与内存实测、App Store 打包上架。

| 阶段 | 可在 Windows 完成 | 必须 macOS |
|---|---|---|
| 阶段 1 | 逻辑迁移、`expect` 声明、`iosMain` 代码编写、配置草稿、CI 配置 | 归档 cinterop 编译、Xcode 工程、真机联调、性能验证 |
| 阶段 2 | Ktor/WebDAV 逻辑改写、OAuth 逻辑、`ASWebAuthenticationSession` 代码草稿 | URL scheme/Info.plist 落地、授权流程真机验证、Keychain |
| 阶段 3 | 大部分逻辑与 UI 代码 | PDFKit 接入、分享扩展、签名与上架 |

**结论**：**在拿到一台 Mac（或可用的 macOS CI）之前，iOS 版无法真正交付验证。** 建议：先在 Windows 上完成阶段 1 的「可共享逻辑迁移 + `iosMain` 代码 + 配置草稿」（约占阶段 1 的 40–50% 工作量），期间并行筹备 Mac 环境；一旦具备 Mac，再做编译联调。

---

## 5. 参考资料

**Kotlin / Compose Multiplatform**
- Compose Multiplatform 仓库与 release（最新 1.12.1，2026-09-22）— https://github.com/jetbrains/compose-multiplatform
- Compose Multiplatform 版本兼容性 — https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- KMP 平台稳定性（iOS/Compose 均 Stable）— https://kotlinlang.org/docs/multiplatform/supported-platforms.html
- 多平台 Jetpack 库打包方式 — https://kotlinlang.org/docs/multiplatform/compose-multiplatform-jetpack-libraries.html
- Navigation and routing（KMP）— https://kotlinlang.org/docs/multiplatform/compose-navigation-routing.html
- 添加 iOS 依赖（cinterop / CocoaPods）— https://kotlinlang.org/docs/multiplatform/multiplatform-ios-dependencies.html
- State of Kotlin 2026（CMP iOS Stable 时间线）— https://devnewsletter.com/p/state-of-kotlin-2026/

**AndroidX / Jetpack KMP**
- KMP 支持的 Jetpack 库总览（含各库最新版本）— https://developer.android.com/kotlin/multiplatform
- Room release notes（KMP 自 2.7.0；2.8.5 为 2026-09 最新）— https://developer.android.com/jetpack/androidx/releases/room
- Room KMP 配置指南 — https://developer.android.com/kotlin/multiplatform/room
- Room 3.0 公告（2026-03）— https://android-developers.googleblog.com/2026/03/room-30-modernizing-room.html
- DataStore KMP（自 1.1.0）— https://developer.android.com/kotlin/multiplatform/datastore
- DataStore release notes — https://developer.android.com/jetpack/androidx/releases/datastore

**网络 / 图片**
- Ktor Client engines（Darwin = NSURLSession）— https://ktor.io/docs/client-engines.html
- Ktor 多平台教程 — https://ktor.io/docs/client-create-multiplatform-application.html
- Coil 3 升级指南（KMP，`io.coil-kt.coil3`）— https://coil-kt.github.io/coil/upgrading_to_coil3/
- Coil 网络图片（ktor3 / okhttp 插件）— https://coil-kt.github.io/coil/network/
- Coil changelog — https://coil-kt.github.io/coil/changelog/

**归档 / 压缩**
- SWCompression（Swift，ZIP/TAR/7-Zip 读取）— https://github.com/tsolomko/SWCompression
- UnrarKit（iOS/macOS RAR 读取，封装 UnRAR 5.8.1）— https://github.com/abbeycode/UnrarKit
- ZIPFoundation（Swift ZIP）— https://github.com/weichsel/ZIPFoundation
- kmp-zip（Kotlin 多平台 ZIP/GZIP）— https://github.com/henrik242/kmp-zip

**文件访问 / 系统集成**
- FileKit（KMP 文件选择器 + BookmarkData）— https://github.com/vinceglb/FileKit
- FileKit Bookmark Data 文档 — https://filekit.mintlify.app/core/bookmark-data
- Apple：Providing access to directories（security-scoped URL）— https://developer.apple.com/documentation/uikit/providing-access-to-directories
- Apple：`NSURL`（bookmark / security-scoped）— https://developer.apple.com/documentation/foundation/nsurl
- Apple：`UIDocumentPickerViewController` — https://developer.apple.com/documentation/uikit/uidocumentpickerviewcontroller
- Apple：`ASWebAuthenticationSession` — https://developer.apple.com/documentation/authenticationservices/aswebauthenticationsession

**本项目相关**
- 项目仓库：GitHub `TerryYu12/xy-reader`
- Google Drive 配置说明：仓库内 `GOOGLE_DRIVE_SETUP.md`

---

*文档版本：2026-09-30 · 基于 `app/build.gradle.kts`（appVersionName 0.4.5 / versionCode 17）与 `app/src/main/java` 全部 62 个 Kotlin 文件（约 16.6k 行）现状盘点，以及 2026-09-30 的公开资料调研。所有版本号与支持状态均来自上述来源，未在 iOS 上实测。*
