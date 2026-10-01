# 任务：XY reader 漫画双击放大改为「整本统一缩放」+ 上下模式动画锚定修复（0.4.9）

仓库：F:\AI Flies\hermes work\XY reader（git 仓库，Windows，git-bash，路径含空格要引号）
当前版本：0.4.8（本次升 0.4.9）。构建热缓存，gradle 命令都能跑。

## 背景与现状（已核实，直接读码对齐）

漫画阅读器在 `app/src/main/java/com/xyreader/reader/ReaderScreen.kt` 的 `ReaderPagerArea`（约 420-677 行）+ `ReaderPage`（约 1557-1614 行）；手势与数学在 `PageZoom.kt`。

两种阅读模式：
- **上下连续模式（UP_DOWN）**：LazyColumn + `columnZoomState` 整列缩放。双击已调 `zoomAnchored`（整列等比、锚定 focal），但**动画期间** scale 用 `animate` 逐帧变、滚动偏移却在动画结束后才 `requestScrollToItem` —— 动画 200ms 内内容漂移、结尾瞬跳（用户感知：连续页不衔接）。
- **左右翻页模式（横向 Pager，日漫 RTL）**：所有页共用同一个 `pageZoom`（PageZoomState，graphicsLayer scale/offset 相同），**但** `LaunchedEffect`（约 491-496 行）在翻页时 `pageZoom.reset()`，且 `userScrollEnabled = pageZoom.scale <= 1f`（约 665 行）缩放中禁翻 —— 实际体验 = 每页单独放大、翻页即弹回 1x。

## 需求（用户原话：点击翻页只对阅读的左右翻页模式生效，漫画双击放大的时候要所有页面一起放大，连续页要完美衔接，而不是单独放大（放大原点同一个））

1. **左右翻页模式 + 图片书（漫画，`!isTextNovel`）**：双击放大 = 整本统一缩放——
   - 翻页**不再复位**缩放（去掉/条件化 LaunchedEffect 里的 `pageZoom.reset()`：仅 `isTextNovel` 时保留复位，漫画横向模式翻页保持缩放）；
   - 所有页共用同一 scale + offset（已经共用同一 PageZoomState，保持），即「放大原点同一个」；
   - 缩放中（scale>1）**点按翻页可用**（tap 三分区的 onTap 现在就未被 pageZoom 消费，验证即可；`userScrollEnabled` 维持 false 防拖拽冲突，翻页走点按分区——本就是「点击翻页只对左右翻页模式生效」）；
   - 再双击 → 整本缩回 1x（offset 清零）。
2. **上下连续模式**：双击/捏合的整列缩放**动画期间逐帧锚定**——把 `requestScrollToItem` 挪进 `animate` 的逐帧回调（每帧按当前 scale 重算 locate 并滚动），动画结束状态与现逻辑一致；「连续页完美衔接、不漂移、不瞬跳」。
3. **点按翻页只在左右翻页模式**：上下模式的 onTap 保持只呼出/收工具栏（现状如此，勿改出翻页行为）。
4. 文字小说（isTextNovel）横向模式行为不变（翻页复位）。

## 硬性约束

- 不改其它模块；新增纯函数逻辑放 PageZoom.kt 并配单元测试（现有 test 目录风格）；
- `LaunchedEffect` 复位条件化时注意 Compose 重组正确性（isTextNovel 是 State，读在 effect 内）；
- RTL（日漫右开本）下点按分区逻辑不动；
- 版本号：`app/build.gradle.kts` 的 `appVersionName` 升 "0.4.9"（versionCode 21）。

## 交付步骤（照做，别跳）

1. 读码对齐上述行号与逻辑；
2. 实现 + 单元测试；
3. `./gradlew :app:testDebugUnitTest --console=plain` 全绿；
4. `./gradlew :app:assembleRelease :app:lintDebug --console=plain` 全绿；
5. aapt 核验：`$LOCALAPPDATA/Android/Sdk/build-tools/*/aapt.exe dump badging <apk>` 必须 versionName=0.4.9 且 uses-permission 含 android.permission.INTERNET；
6. 拷贝 `app/build/outputs/apk/release/app-release.apk` → `outputs/XY-READER-0.4.9.apk`；
7. 写验证文档 `docs/verification/2026-10-01-book-zoom-0.4.9.md`（改动/机制/实测输出/真机清单）；
8. git add + commit（中文消息，**不 push**）；
9. 最终回复输出：改动文件清单、逐条实现说明、三步命令真实输出尾部、commit 哈希、遗留问题。
