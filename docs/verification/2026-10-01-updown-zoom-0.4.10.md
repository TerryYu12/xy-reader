# 0.4.10 验证档 —— 上下滚动模式缩放「只改框、不放大图」修复

日期：2026-10-01
版本：**0.4.10 / versionCode 22**
交付物：`outputs/XY-READER-0.4.10.apk`（22,541,909 B；md5 `c32689eb4b8a05f73640a26420dc9603`）
任务书：`docs/superpowers/plans/2026-10-01-updown-zoom-0.4.10-fix.md`

## 缺陷（用户真机复现，0.4.9 必现）

上下滚动模式（`PageMode.UP_DOWN`）下双击 / 双指捏合**不放大页面内容**：缩放只作用在 LazyColumn 项的高度
（`aspectRatio(aspect / listScale)`）与锚定滚动上，`ReaderPage` 内的 Image 绘制宽度约束恒为视口宽
→ 视觉尺寸完全不变（实测线距 36px 缩放前后一致）。用户体感 = 画面被挪一下、框下方多出大片空白。
横向（`LEFT_RIGHT`）模式无此问题，本次未动其路径。

## 改动清单

1. `app/src/main/java/com/xyreader/reader/ReaderScreen.kt`
   - `ReaderPage` 新增参数 `renderScale: () -> Float = { 1f }`；
   - `zoomState == null` 分支（连续列表）在原有 `translationX = panX()` 之外，对图像本体施加
     `scale(renderScale())`，使图像与页框同步等比放大；
   - 上下模式调用处传 `{ columnZoomState.scale }`；横向分支与其它调用点保持默认 `{ 1f }`，行为零变化。
2. `app/src/main/java/com/xyreader/data/WebDavClient.kt`
   - 新增 `isDavServer(url): Boolean`：PROPFIND Depth:0，**仅 207 Multistatus 视为可用**。
3. `app/src/test/java/com/xyreader/data/WebDavLocalServerTest.kt`
   - 探活由「TCP connect」改为 `client.isDavServer(base)`（见下「WebDav 自动跳过语义」）。
4. `app/src/test/java/com/xyreader/reader/UpDownZoomRenderTest.kt`（新，像素级回归）
5. `app/build.gradle.kts`：`appVersionName = "0.4.10"`、`versionCode = 22`。

未改动：`ContinuousZoomMath` / `columnZoom` / `pageZoom` / `calculateZoom` 等手势与锚定数学；
横向模式代码路径；未引入依赖；未做无关重构。

## 机制说明

### 为什么用 canvas 变换而不是 `graphicsLayer`

任务书建议 `graphicsLayer { scaleX = s; scaleY = s }`。实现改用了等价绘制的另一条路：

```kotlin
Modifier.drawWithContent {
    withTransform({
        translate(panX(), 0f)
        scale(renderScale(), renderScale(), pivot = center)
    }) { this@drawWithContent.drawContent() }
}
```

两者绘制结果一致，但 `graphicsLayer` 会把**本节点的 `boundsInRoot` 一并放大 s 倍**。而既有的
`ContinuousZoomAnchorTest` 正是用页面语义节点的 `boundsInRoot` 反推缩放倍数（该文件 36/61/69 行）——
一旦走图层，该测试的口径就失真。canvas 变换只影响绘制，不动布局与语义坐标，故既有锚定测试与
新像素测试能同时成立。

几何自洽：`ContentScale.Fit / FillWidth` 都把图**居中**绘在框内，故框中心 = 图中心；绕中心放大 s 倍后
图恰好填满 `baseH × s` 的框，页与页首尾相接、不重叠；`s = 1` 时与改动前完全等价。

### WebDav 自动跳过语义（探活为何要 DAV 感知）

`WebDavLocalServerTest` 的设计意图是「本地 8899 没有测试服务器时自动跳过」。原探活只用 TCP connect，
而本机 8899 被另一程序的 devserver 占用（对 PROPFIND 返回 **501**）→ connect 成功、被误判为「服务器可用」，
于是跳过失效、在断言处报错。

改为最小 PROPFIND 探活后：**8899 未监听 → 跳过；监听但非 DAV（如本次的 501）→ 同样跳过**；
只有真正返回 207 Multistatus 才执行断言。CI 环境可安全存在，本机被占用也不再是假失败。

## 反证核验（必做项）

把 `ReaderScreen.kt` upDown 分支 `withTransform` 里的 `scale(renderScale(), ...)` 一行临时注释掉
（保留 `translate(panX(), 0f)`），跑同一命令。

### ① 注释掉后（必须失败）

```
$ ./gradlew :app:testDebugUnitTest --tests "com.xyreader.reader.UpDownZoomRenderTest" --console=plain

UpDownZoomRenderTest > 上下模式双击后页面内容真的放大 2_5 倍 FAILED
    java.lang.AssertionError at UpDownZoomRenderTest.kt:222

UpDownZoomRenderTest > 上下模式捏合后页面内容与布局同步放大 FAILED
    java.lang.AssertionError at UpDownZoomRenderTest.kt:261

2 tests completed, 2 failed

> Task :app:testDebugUnitTest FAILED

FAILURE: Build failed with an exception.
```

失败原因是**断言**而非编译错误（确认过：`allWarningsAsErrors` 未开，残留的未用 import 不会阻断编译）。

从 JUnit XML 取到的断言消息（证明失败点正是「图没放大」）：

```
java.lang.AssertionError: 双击后页面内容应放大到 2.5x（不是只撑高框） expected:<2.5> but was:<1.0>
java.lang.AssertionError: 捏合后内容应确实放大（实测 1.0）
```

即实测比值恒为 1.0 —— 与 0.4.9 的缺陷表现完全一致，测试确实咬住了这个缺陷。

### ② 恢复后（必须通过）

```
$ ./gradlew :app:testDebugUnitTest --tests "com.xyreader.reader.UpDownZoomRenderTest" --console=plain
...
BUILD SUCCESSFUL in 7m 1s
30 actionable tasks: 6 executed, 24 up-to-date
```

## 测试统计

```
$ ./gradlew :app:testDebugUnitTest --console=plain
...
BUILD SUCCESSFUL in 2m 20s
```

XML（`app/build/test-results/testDebugUnitTest/*.xml`，26 个套件）汇总：

| tests | failures | errors | skipped |
|---|---|---|---|
| **109** | **0** | **0** | **2** |

2 个 skipped 即 `WebDavLocalServerTest` 的两例（`listRootDirParsesEntries` / `listChineseDirParsesFiles`）。
**跳过的语义**：本机 8899 被非 DAV 服务占用（`curl -X PROPFIND` 返回 501），探活判定不可用 → Assume 跳过。
这**不是**「测试被删掉或永久禁用」：在真跑 wsgidav 的机器上（命令见测试类头注释），这两例会真实执行。

新增的 `UpDownZoomRenderTest` 两例均通过（含像素级 marker 测量与双击点锚定 ±10px）。

## 构建与产物核验

```
$ ./gradlew :app:assembleRelease --console=plain
...
BUILD SUCCESSFUL in 18m 47s
```

```
$ ./gradlew :app:lintDebug --console=plain
BUILD SUCCESSFUL in 1m 7s
$ tail -1 app/build/reports/lint-results-debug.txt
0 errors, 20 warnings
```

> 环境噪音记录：`assembleRelease :app:lintDebug` 合并跑时，`lintAnalyzeDebugAndroidTest` **首次崩溃**——
> `Unexpected failure during lint analysis of PdfGeometryTest.kt`，
> `KotlinIllegalArgumentExceptionWithAttachments ... LLFirModuleLazyDeclarationResolver`（lint 的 K2/FIR 前端 bug）。
> 该文件位于 `src/androidTest`、自 0.4.8 起未改动，与本次 diff 无关；**单独重跑 `:app:lintDebug` 立即全绿**
> （0 errors, 20 warnings），确认是偶发。故拆分为两条命令执行。

aapt（build-tools 35.0.0）：

```
package: name='com.xyreader' versionCode='22' versionName='0.4.10' ...
uses-permission: name='android.permission.INTERNET'
```

交付件：`app/build/outputs/apk/release/app-release.apk` → `outputs/XY-READER-0.4.10.apk`
（22,541,909 B，md5 `c32689eb4b8a05f73640a26420dc9603`）。签名沿用发布证书。

## 待真机确认（用户）

1. 上下连续模式 → 双击：页面内容应真正放大 2.5×，双击点处内容停在原位不漂移。
2. 上下连续模式 → 双击缩回 1x：动画平滑、页页首尾相接、框下方不再出现大片空白。
3. 上下连续模式 → 双指捏合放大：内容随手指等比放大；捏合后单指水平平移、纵向滚动无回归。
4. 换一本宽高比不同的书重复 1–3（不同 `aspect` 下框高与缩放倍数的乘积关系应仍成立）。
5. 横向翻页模式（漫画/图片书）双击与捏合：确认行为与 0.4.9 一致，无回归。
6. 文字小说（TXT / 文字版 EPUB）上下模式：字号与排版无异常。

## 遗留问题与发现（未在本次改动范围内）

1. **lint 的 K2 偶发崩溃**（见上）：与代码无关，但会污染合并构建的退出码。建议 CI 里把 lint 与
   assemble 拆成两条命令，或对 `lintAnalyzeDebugAndroidTest` 加重试。
2. `WebDavLocalServerTest` 的两例在本机长期处于「跳过」态，等于本地无覆盖；若要常跑，需固定一个
   非 8899 的端口起 wsgidav，或让探活接受可配置的基准 URL（当前 `base` 硬编码 8899）。
3. 上下模式捏合仍是「逐事件」锚定（`zoomAnchored`），无动画故无漂移；与双击的逐帧锚定共用同一数学。

## 说明

- 本次只动 `reader` / `data` 包、对应测试与版本号，未改其他模块、未引入依赖。
- 反证核验与全量回归均在**同一工作树**上完成，两次运行仅差被注释的那一行。
