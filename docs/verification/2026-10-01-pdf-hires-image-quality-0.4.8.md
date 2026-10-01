# 0.4.8 — PDF 高分渲染 + 漫画图片渲染质量切换

日期：2026-10-01
状态：已构建交付（outputs/XY-READER-0.4.8.apk，md5 cbb3d911d821229eb9655ad2ef2b6c01），**未真机验证**

## 改动

1. **PDF 高分渲染（默认生效，真修复）**
   - `PdfPageSource`：渲染分辨率从 PdfRenderer 默认 72dpi 点尺寸改为「屏幕短边 × 1.5」，
     显式 transform matrix 放大 + `RENDER_MODE_FOR_PRINT`（高保真模式）。
   - 根因：默认 72dpi 出图（A4=595×842px）手机全屏需拉伸近 2 倍，必然模糊。
   - 新增 `PdfRenderMath`（纯函数，带单测 PdfRenderMathTest）：放大余量 1.5、
     单边上限 4096（GPU 纹理上限）、总像素上限 12M（防 OOM）。

2. **漫画「图片渲染质量」切换（设置→显示组新增卡，默认标准）**
   - `ImageQuality` 枚举：标准（0.4.7 历史行为）/ 高清（多级重采样预缩）。
   - `ImageDownscale`：高清档用 `BitmapCompat.createScaledBitmap`（逐级减半，
     接近 mipmap）把大图缩到「屏幕短边×2」再交给 GPU（缩小比例 ≤2 倍）。
   - PDF/文字页跳过预缩（渲染尺寸已适配/天然贴合屏幕）。
   - 质量切换免重开数据源：`ReaderViewModel` 监听档位变化，失效在途结果+清缓存+重渲染当前页。

## 客观测量（2026-10-01 凌晨，真机模拟器截图 0.4.7 vs 0.4.8，普通漫画 2400×3600）

修正页面边界（y390-2009）后三指标一致：0.4.8 高清档比 0.4.7 标准档
拉普拉斯方差 -36.9%、高频能量 -35.2%、局部对比度 -17.9%。

**机制定案（不是 bug，是取舍）**：单步 0.45x 双线性（0.4.7）欠采样产生混叠，
网点出假高频（摩尔纹），拉普拉斯虚高；多级预缩（0.4.8）是正确的抗混叠重采样，
更干净但更柔。桌面模拟（PIL）复现 -33.2%，与真机 -36.9% 吻合。

**处置**：数值不能替人眼定美学优劣 → 预缩档默认关（ STANDARD，
不改变 0.4.7 现状），设置项保留；用户真机对比后喜欢再开。
PDF 高分渲染是真修复（放大糊是硬缺陷），保持默认生效。

## 验证清单

- [x] `:app:testDebugUnitTest` 全绿（1m，BUILD SUCCESSFUL）
- [x] `:app:assembleRelease :app:lintDebug` 全绿（1m13s）
- [x] aapt：versionName=0.4.8 / versionCode=20 / INTERNET 权限在
- [x] outputs 拷贝 + md5 一致（cbb3d911…）
- [ ] **真机验证（必须用户做）**：PDF 打开即应明显更清晰（0.4.7 糊 → 0.4.8 锐）；
      漫画在设置里切 标准/高清 对比观感（数据上高清=少摩尔纹更柔，标准=锐但可能有摩尔纹）

## 遗留

- 版本号已在 build.gradle.kts 升到 0.4.8（versionCode 20），但本次未发 GitHub Release
  （等真机验证后连同 Run 1 级别的验证一起发）。
