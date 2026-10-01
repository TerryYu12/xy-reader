# 任务：上下滚动模式缩放「只改框、不放大图」修复 —— 双击/捏合必须真正放大页面内容（0.4.10）

仓库：F:\AI Flies\hermes work\XY reader（git 仓库，Windows git-bash，路径含空格要引号）
当前版本：0.4.9（本次升 0.4.10，versionCode 22）。构建热缓存，gradle 命令都能跑。

## 缺陷（已实测复现，0.4.9 必现，用户真机同症状）

上下滚动模式（PageMode.UP_DOWN）下，双击与双指捏合**不会放大页面内容**：

- 缩放只改变了 LazyColumn 项的高度（`Modifier.aspectRatio(aspect / listScale)`）与滚动位置；
- 图片本体（ReaderPage 内 Image，`ContentScale.Fit/FillWidth`）绘制宽度约束恒为视口宽 →
  视觉尺寸完全不变（实测：4 页 PDF，页面线距 36px、粗条 57px 高，缩放前后一致 = 1x）；
- 用户体感 = 双击/捏合后画面被挪一下、框下方多出大片空白，页面没有被放大。
- 横向（LEFT_RIGHT）模式无此问题（MuMu 实测正常：双击精确 2.5x 中心放大、捏合按手势几何比例 2.70x，
  数值拟合 diff 与「原图不变」假设差一个量级），勿动横向路径。

复现证据（MuMu 模拟器 + 自家 4 页 PDF + sendevent 多指注入，2026-10-01）：

- 双击 (540,800)：页 1 项高度 1620→4050px（scale=2.5 只在布局上生效），但内容线距仍 36px、
  粗条仍 57px；Fit 把 1080×1620 的图居中在 4050 高的框里 → 图上边停在框内 1215px 处、
  屏幕下半全黑。uiautomator 佐证：第 1 页可见下沿 2173px（= 4050 − 滚动 1877）。
- 捏合（手指距 280→760px，ratio 2.71）：页项 1620→4390px，内容线距仍 36px。
- 对照横向模式：双击后「中心裁剪×2.5」模型 diff=14.7，「原图不变」假设 diff=110。

## 根因（已定位到行）

`ReaderScreen.kt` 上下模式分支：
- LazyColumn 项：`Modifier.fillMaxWidth().aspectRatio(aspect / listScale)`（约 609-615 行）
  只把「框」纵向撑大；
- `ReaderPage`（约 1569-1626 行）在 `zoomState == null` 分支里，
  Image 仅做 `graphicsLayer { translationX = panX() }` —— 图像从未按 listScale 放大。

即：缩放倍数只作用于布局高度与锚定滚动（ContinuousZoomMath），没有作用于图像内容本身。

## 修复要求

1. **上下模式页面内容随 `columnZoomState.scale` 真正等比放大**：
   - 图像在框内是居中绘制的（Fit 居中；FillWidth 同理），框高 = baseH×s 时，
     对 Image 施加 `graphicsLayer { scaleX = s; scaleY = s }`（默认中心原点）即可恰好填满框、
     页与页保持首尾相接、不重叠、s=1 时完全等同现状；
   - 建议：`ReaderPage` 新增参数（如 `layerScale: () -> Float = { 1f }`），
     在 `zoomState == null` 分支的 graphicsLayer 同时设 `scaleX/scaleY`（保留 `translationX = panX()`）；
     上下模式调用处传 `{ columnZoomState.scale }`；横向分支与其它调用点保持默认 {1f}，行为零变化。
   - 水平平移语义保持：内容宽 = 视口宽×s，最大平移 ±视口宽×(s−1)/2（现有 `clampPanX` 已按此假设，勿改）。
2. **锚定在新渲染下成立**：双击点处的文档内容在缩放动画前后停在原位（像素级检查，±10px 内）；
   连续页保持相接（页 2 顶 = 页 1 底）。
3. **横向模式行为不许变化**（其全部测试必须保持全绿）。
4. 不改手势 math（ContinuousZoomMath / columnZoom / pageZoom），除非有测试证明必要。
5. **新增视觉级回归测试（关键！本缺陷正是因为旧测试只测状态与滚动、没测像素才漏掉）**：
   - 用 `@GraphicsMode(NATIVE)` + 合成 cbz（页面图含已知几何 marker，例如 600×400 图中
     y=50..100 黑色横条），参考 `MangaUnifiedZoomTest` 的 Compose 测试环境写上下模式用例：
     a) 1x 下截图/捕获 → 量得 marker 高 h0；双击 → 推帧（advance 辅助）→ 再捕获 → 量得 h1；
        断言 h1/h0 ≈ 2.5（容差 ±0.3）；
     b) 捏合路径覆盖：可用 `performTouchInput` 多指手势或等价手段触发 `columnZoomState.scale` 变化后
        做同样的像素断言（至少保证与双击共用同一渲染路径被真实验证）。
   - 渲染结果读取：优先 `captureToImage()`（Robolectric NATIVE 下可用性先在用例里实测；
     若受限，退而求其次用 composable 的 draw 捕获位图，但**必须真读像素**，不许只断言状态/语义）。
   - **反证核验**：把修复（graphicsLayer 的 scale）临时注释掉，b) 类用例必须失败；恢复后全绿。
     在汇报里给出这两个真实输出。
6. 现有全部测试保持通过（特别是 ContinuousZoomAnchorTest / MangaUnifiedZoomTest / ContinuousZoomMathTest）。

## 交付步骤（照做，别跳）

1. 读码对齐上述行号与逻辑；确认横向模式相关代码零改动；
2. 实现修复 + 视觉测试；
3. `./gradlew :app:testDebugUnitTest --console=plain` 全绿（XML 统计为准）；
4. `./gradlew :app:assembleRelease :app:lintDebug --console=plain` 全绿；
5. aapt 核验：`$LOCALAPPDATA/Android/Sdk/build-tools/*/aapt.exe dump badging <apk>`
   必须 versionName=0.4.10 且 uses-permission 含 android.permission.INTERNET；
6. 拷 `app/build/outputs/apk/release/app-release.apk` → `outputs/XY-READER-0.4.10.apk`
   （若用 scripts/copy-delivery.py，先确认它读取的版本号确为 0.4.10）；
7. 写验证文档 `docs/verification/2026-10-01-updown-zoom-0.4.10.md`（改动/机制/实测输出/真机清单）；
8. `git add`（含本任务书与验证文档）+ commit（中文消息，**不 push**）；
9. 最终回复输出：改动文件清单、逐条实现说明、三步命令真实输出尾部、反证核验输出、
   commit 哈希、遗留问题。

## 附：本机复现指引（如需自查）

- MuMu 模拟器（`E:\MuMuPlayer\nx_main\adb.exe -s 127.0.0.1:16384`）已装 0.4.9 + 4 页测试 PDF，
  当前处于 UP_DOWN 模式；多指注入脚本模板见 skill `mumu-emulator-ops` 与
  `F:\AI Flies\Hermes\cache\scratch\pinch.sh`、`swipe.sh`（sendevent 必须带 BTN_TOOL_FINGER/BTN_MOUSE 键位）。
- 双击注入：`input tap 540 800; sleep 0.13; input tap 540 800`（间隔 <40ms 会被 Compose 丢弃）。
- 度量方法：截图纵向亮度剖面找暗段间距（线距 36px=1x），或对「拉伸模型」做最小二乘拟合。
