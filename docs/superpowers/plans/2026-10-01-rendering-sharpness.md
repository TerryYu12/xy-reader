# 图片渲染清晰度优化：PDF / 漫画模糊问题调研与方案

> 2026-10-01 调研。用户反馈：PDF 导入后看不清；漫画阅读时模糊。本文含代码级诊断、业界调研、修复方案。**待拍板后实施。**

## 结论摘要

| 场景 | 根因 | 性质 | 修复 |
|------|------|------|------|
| PDF | 按 PDF 原始点数（72dpi）光栅化，A4 只出 595×842px，屏幕显示需放大 ~1.8 倍 | **确诊 bug** | 按屏幕物理分辨率 × oversample 渲染（含 Matrix 缩放 + FOR_PRINT） |
| 漫画 | 大图缩小到屏幕时（常见 2 倍以上），Compose 默认 `FilterQuality.Low`（纯双线性、无 mipmap）欠采样，线条/网点细节损失 | **高度定位**（待真机佐证） | `filterQuality = Medium`（mipmap）或 `High`（bicubic） |
| 深层（可选） | 双指放大超过源图分辨率时永远糊（物理极限）；超大图（>GPU 纹理上限）有降级风险 | 进阶项 | 参考 Mihon 引入 subsampling 组件（Telephoto）等 |

## 一、诊断（代码级证据）

### 1.1 PDF：渲染尺寸只有 72dpi 的点尺寸

`app/src/main/java/com/xyreader/archive/PdfPageSource.kt` L39-44：

```kotlin
val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
bitmap.eraseColor(Color.WHITE)
page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
```

- `page.width/height` 是 PDF 页在 **72dpi 下的点尺寸**（A4 = 595×842，Letter = 612×792），不是像素需求。
- 典型 1080 宽手机全屏显示 → 595 被拉伸到 ~1080（1.82 倍）→ 每显示像素不足 0.55 源像素 → 必然模糊；滚页/双指放大时更糊。
- `RENDER_MODE_FOR_DISPLAY` + `transform=null`：未利用官方为缩放场景提供的 Matrix 参数；社区实践高清渲染用 `RENDER_MODE_FOR_PRINT`。
- 对比：Android 官方文档明确 `transform` 用于"从 72dpi 点坐标缩放到目标像素坐标"，且"clip + transform 可用于实现 tile 渲染（缩放场景）"。

### 1.2 漫画：缩小采样质量不足

- 解码端无问题：`PageSources.decodeEntryStream` 是**全分辨率解码**（无 inSampleSize 降采样），这是好的。
- 显示端：`ReaderScreen.kt` L1573 `Image(bitmap, contentScale = FillWidth/Fit)` + `graphicsLayer` 缩放。
- Compose `Image` 的 `filterQuality` **默认为 `FilterQuality.Low` = 纯双线性**（官方文档：Low=bilinear、Medium=bilinear+mipmap、High=bicubic）。
- 典型漫画扫描件 1600–2400px 宽，1080 屏上缩小 1.5–2.2 倍：双线性欠采样会把高频细节（线条、网点/截网）抹掉，视觉即"糊/不锐"。
- 双指放大（上限 5x）时是 GPU 双线性拉伸；放大到超过源图分辨率后必然软（物理极限，任何阅读器都如此）。

### 1.3 其它已核对项（非问题）

- 页位图缓存 `PageBitmapCache` 按字节 LRU（48–128MB），逻辑正常。
- minSdk 26；API 28+ 设备的 GPU 缩放大图无"显著质量退化"（官方 hardware-accelerated 文档），26/27 老设备例外。
- 超大图（单边 > 设备 maxTexture 4096~16384）的降级风险仅影响极端超高清源，作为备选防护项。

## 二、业界调研："别人怎么搞的"

1. **Mihon / Tachiyomi（漫画阅读器标杆）**
   - 分页模式把**原图流**直接交给 deep-zoom 组件 SubsamplingScaleImageView：先解码低分辨率全图快速显示，**放大时用 BitmapRegionDecoder 按需重解码高分辨率 tile 区域**（"显示巨大图像且放大不丢细节"，支持到 20000×20000px）。
   - 关键配置：`setMinimumTileDpi(180)`、`setMinimumDpi(1)`（源码 PagerPageHolder / ReaderPageImageView 实测确认）。
   - webtoon 模式才用 Coil 解码到视图尺寸（省内存）。
   - 社区 issue #42 承认其缩放算法（双线性）对高清数字版偏软，对比 Perfect Viewer 略逊。
2. **Perfect Viewer（老牌漫画阅读器）**：使用 **Lanczos 3** 重采样，社区反馈高清漫画"drastically better / 明显更锐"（Mihon issue #42 多条对比评论）。
3. **Compose 生态现成件（"别人搞的轮子"）**：
   - **saket/telephoto**（1.2k★）：`ZoomableImage` 是 `Image()` 的 drop-in 替换，自带大图自动 subsampling（tile 懒加载）；`SubSamplingImageSource` 支持自定义源（**文档明确举例可用于 PDF**）。这是 Compose 版 SSIV。
   - panpf/zoomimage（Compose Multiplatform）；K1rakishou/ComposeSubsamplingScaleImage（SSIV 局部移植）。
4. **PDF 渲染社区标准做法**（StackOverflow 经典问答 53567827 "PdfRenderer shows very poor quality"）：
   - `bitmapW = densityDpi × pageWidth / 72`（按屏幕 DPI 放大位图再渲染），配 `RENDER_MODE_FOR_PRINT`；
   - 或按屏幕宽度倍数放大 + Matrix 缩放（官方 tile rendering 思路）。

## 三、修复方案（分档）

### A. PDF 高分渲染（推荐必做）

- `PdfPageSource.renderPage` 改为按目标像素宽渲染：
  - `scale = max(1f, 视口宽px × oversample / page.width)`，`oversample` 初值 **1.5**（覆盖 1.5x 双指放大内清晰）；
  - 上限保护：像素总量/长边封顶（例如长边 ≤ 4096），防超长页 OOM；
  - `Matrix().setScale(scale, scale)` 传给 `page.render`，并用 `RENDER_MODE_FOR_PRINT`；
- 视口宽度来源：打开时读 `resources.displayMetrics`（竖屏宽），宽屏/平板同样受益。
- 成本：A4@1080屏×1.5 ≈ 1620×2291 ≈ **14.5MB/页**（ARGB），LRU 预算内可容 3-8 页；单页光栅化时间略增（毫秒级）。
- 可选阶段 2（本次可不做）：双指放大到 >1.5x 时按当前缩放重渲染（工程中等）。

### B. 漫画高质量滤波（推荐做）

- `ReaderPage` 的 `Image` 增加 `filterQuality = FilterQuality.Medium`（mipmap：缩小时最明显改善，性能开销小）；
  - 若追求更锐（放大场景也受益）可用 `High`（bicubic，GPU 开销略大）。
- 如当前 Compose 版本 `Image` 无该参数，等价做法：`Image(painter = BitmapPainter(bitmap, filterQuality = ...))`。
- 成本：mipmap 使大图内存 +33%（仅大图 + 一次性生成）；可在低端机上再调。
- 预期：2400→1080 缩小场景细节/网点保留明显改善。

### C. 进阶（可选，单独拍板）

- **C1. 引入 telephoto（Compose 版 subsampling）**：对标 Mihon 的"放大不糊"，超大图 + 高倍缩放的最优解；但替换手势/缩放/连续模式链路，属中大改造。
- **C2. Lanczos 预缩**：显示前把超 2x 缩小的图用 Lanczos 重采样（对标 Perfect Viewer）；中等工程量，需引库或自研。
- **C3. 超大图防护**：>maxTexture 时主动分步降采样，规避 HWUI 强制降级（极端源才受影响）。

## 四、验收方式（实施后）

1. `PdfGeometryTest` 扩展：断言渲染尺寸 > 页面点尺寸、缩放比例正确、上限生效；`:app:testDebugUnitTest` 全绿。
2. MuMu 模拟器：安装 release、放入高清 PDF + 漫画样本，截图对比修复前后锐度（可量化：拉普拉斯方差/FFT 高频能量，与历史方法论一致）。
3. 真机体验交由用户（分辨率、流畅度、内存）。
4. 如需最准定位：用户提供"最糊的一本漫画 + 机型"，核对其源分辨率与屏幕比。

## 五、待拍板

- [ ] 实施范围：A/B 基础修复先行，还是含 C 进阶项？
- [ ] 漫画滤波档位：Medium（保守，缩小时改善）还是 High（更锐，开销略大）？
