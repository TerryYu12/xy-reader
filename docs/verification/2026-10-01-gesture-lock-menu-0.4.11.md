# 0.4.11 验证档 —— 手势锁入口随菜单出现 + 右侧常驻锁钮移除（旧位置恢复翻页）

日期：2026-10-01
版本：**0.4.11 / versionCode 23**
交付物：`outputs/XY-READER-0.4.11.apk`（22,613,165 B；md5 `b4133eadd7f60a92019ecdf4b38417f9`）

背景：用户要求「阅读界面的锁定改为：点击出现菜单时候一起出现；上锁后点击中间出现锁」。
同时修掉 `2026-10-01-book-zoom-0.4.9.md` 遗留问题 1（右侧中央常驻锁钮与右 1/3 点按翻页分区重叠——
在那个区域点按会误入锁定态而不是翻页）。

## 一、改动清单

1. `ReaderScreen.kt`：
   - **删除**右侧中央常驻锁钮（原 `if (!locked) { Surface(...) }` 块，36dp、`align(CenterEnd)`，360dp 屏 x≈318-354dp）；
   - **新增**顶部工具栏锁定入口（收藏之后）：随菜单一起出现/消失，点击即锁定并收起菜单；
   - 文档块/注释同步（含「六按钮」等顺带修正）。
2. `app/build.gradle.kts`：`appVersionName 0.4.10 → 0.4.11`、`versionCode 22 → 23`。
3. 新增测试 `app/src/test/java/com/xyreader/reader/GestureLockFlowTest.kt`（Robolectric + Compose 全流程 7 组断言）。
4. 三个既有测试注释更新（去掉「避开右中锁钮」的历史坐标理由，坐标保留）：
   `MangaUnifiedZoomTest` / `ContinuousZoomAnchorTest` / `UpDownZoomRenderTest`。
5. `scripts/copy-delivery.py`：拷贝前用 aapt 读 APK 内 versionName 校验，与目标不符（陈旧产物）拒绝拷贝
   （修 `2026-10-01-book-zoom-0.4.9.md` 遗留问题 2）。

## 二、行为语义（0.4.11 起）

- 未锁定：点屏幕中央 → 菜单出现，**锁定入口随菜单一起出现**（顶部最右）；右 1/3 点按正常翻页（不再有锁钮拦截）。
- 锁定：菜单收起；点击静默（左右分区不翻页、不弹任何东西）、双击不缩放；滑动翻页照常；返回键被拦截。
- 解锁：锁定中点屏幕中央 → 右侧中央**呼出满锁形解锁钮**；点它 → 解锁并呼出菜单（锁定入口随菜单回归）。

## 三、单元测试

- 全量：`./gradlew :app:testDebugUnitTest --console=plain` → **BUILD SUCCESSFUL in 1m 17s**；
  XML 统计：**110 tests / 0 failures / 0 errors / 2 skipped**（跳过=WebDAV 本地环境语义，预期）。
- 新增用例：`GestureLockFlowTest` 1/0/0；断言链：初始无锁定入口（不再常驻）→ 旧锁钮位置点按必须翻页
  → 点中央菜单+锁钮出现 → 点锁定 → 锁定中右分区静默且不弹解锁钮 → 锁定中滑动照常 → 点中央呼出解锁钮
  → 点解锁钮解锁且菜单回归。
- **反证核验**：临时把常驻锁钮加回 `ReaderScreen.kt` → 用例按预期失败：
  `java.lang.AssertionError: 初始应无锁定入口（不再常驻）`（GestureLockFlowTest.kt:120），`BUILD FAILED in 8m 12s`；
  恢复后 `git diff` 与改动前**字节一致**（md5 `2d756a7273ae7e7aa2a51575210654b2`），重跑单测绿。

## 四、构建与静态检查

```
> Task :app:assembleRelease
BUILD SUCCESSFUL in 35s

> Task :app:lintDebug
BUILD SUCCESSFUL in 1m 52s
```
lint 报告：**0 errors, 20 warnings**（OldTargetApi / GradleDependency 等历史遗留，与本次改动无关）。

aapt（build-tools 35.0.0）：

```
package: name='com.xyreader' versionCode='23' versionName='0.4.11' ... compileSdkVersion='36'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='com.xyreader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

签名（`apksigner verify --print-certs`）：`CN=XY Reader, O=TerryYu12, C=CN`，SHA-256
`d1cc619dfcb1c5e85f18a1a6d6ed1b614dd44f2a2e2d333f2ebe80ac48b08c35`（与在装 0.4.10 同证书，可覆盖升级）。

## 五、MuMu 真机实测（1080×2400 @420dpi，release 包覆盖升级，数据保留）

**旧版病灶复现（0.4.10，测前状态）**：打开 test4p → 点 (1017,1201)（右侧中央旧锁钮位置）→ 应用被**上锁**；
点屏幕中央出现「解锁手势」徽标（= 用户抱怨的「点那里变成误上锁」现场）。
旧锁钮 uiautomator bounds=[994,1178]-[1041,1225]。截图：
`attachments/2026-10-01-gesture-lock-menu/old-0410-accidental-lock.png`。

**新版（0.4.11）全流程**：

| # | 操作 | 结果 | 证据 |
|---|------|------|------|
| 1 | 呼出菜单 | 顶部出现「锁定手势（防误触）」[943,58]-[1006,121]；菜单收起时**无任何常驻锁钮** | dump；`new-menu-with-lock-entry.png` |
| 2 | 移除对照 | 同模式同页旧/新整屏差异仅 0.27%，且差异全部集中在右中区（旧钮处）；右中裁剪 changed 15.75% | `cmp-rightcenter-old-vs-new.png` |
| 3 | 旧锁钮位置点按 (1017,1201) | **页码 2/4 → 3/4**（翻页成功），无解锁徽标（未上锁） | dump 文本「2/4」「3/4」 |
| 4 | 锁定中点右 1/3 (900,1700) | 前后截图像素**完全一致**（mean=0.00），无徽标 | `new20/new21` 截图对 |
| 5 | 锁定中滑动 (800,1200→200,1200) | 页码 **3/4 → 4/4**（解锁后读页码确认） | dump 文本「4/4」 |
| 6 | 锁定中点中央 | 解锁钮出现 [981,1170]-[1044,1233]；变化集中于右中区（裁剪 changed 19.3%） | `new-locked-badge.png` |
| 7 | 点解锁钮 | 解锁且菜单回归（锁定入口再次出现） | `new-after-unlock-menu-back.png` |
| 8 | 上下滚动模式复测 | 同流程全部通过（入口/锁定/徽标/解锁） | new30-new33 截图 |
| 9 | 收尾 | 阅读模式复原为上下滚动（prefs 字节核对）；设备临时文件已清 | reader_prefs.preferences_pb |

度量方法：`adb exec-out screencap` 截图 + uiautomator dump 取 bounds/文本 + numpy 全图/局部像素平均绝对差（mean/最大差/变化像素占比）。

## 六、备注

- 本次构建的工作区还包含启动图标资源更新（`ic_launcher_background/foreground.png`，并行会话产出），
  随构建进入 APK；该资源改动未纳入本提交。
- 顶部工具栏现为 6 项（返回/目录/书名/书签/收藏/锁定），窄屏下书名可显示宽度相应变小（观察项，非缺陷）。

## 七、待用户确认

1. 锁定入口图标（顶部最右、开锁形）的位置与观感；
2. 解锁钮仍在右侧中央（与原锁钮同位）——若要改为屏幕正中呼出，一行改动；
3. 是否将启动图标 PNG 纳入提交。
