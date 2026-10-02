# 0.5.2 — 自动旋屏（重力感应）开关

日期：2026-10-02　版本：0.5.2 / versionCode 26

## 需求

「加个重力感应（自动旋屏）的开关」+「阅读界面的设置里也加入这个开关」。

## 实现

**三处入口，同一个全局开关（单一数据源）：**

| 位置 | 形态 |
|---|---|
| 设置 → 新增「显示」分组 | 带开关的设置行（图标 `ScreenRotation`，青色调） |
| 阅读配置管理 → 页面 → 「屏幕」卡 | 开关行，在「屏幕常亮」之上 |
| 阅读器内半屏「阅读设置」→ 页面 tab | 开关行（带副标题），在「屏幕常亮」之上 |

**新增文件**

- `data/DisplayPrefsStore.kt` — DataStore `display_prefs`，键 `auto_rotate`（Boolean，默认 true）
- `ui/DisplayPrefs.kt` — `LocalAutoRotate` / `LocalSetAutoRotate`（CompositionLocal 下发）
- `app/src/test/.../DisplayPrefsRoundTripTest.kt` — 默认值 + 写入后跨实例读回

**改动文件**

- `MainActivity.kt` — 顶层持有 store，`LaunchedEffect(autoRotate)` 设置 `requestedOrientation`
- `ui/SettingsScreen.kt` — 新增「显示」分组 + 私有 `SettingSwitchRow`（版式同 `SettingRow`，尾部换 `Switch`）
- `ui/ReaderConfigScreen.kt` — 页面 tab 的「屏幕」`SwitchCard` 增加一条 `SwitchSpec`
- `reader/ReaderScreen.kt` — 半屏面板 `SheetDisplayGroup` 增加开关行；`DisposableEffect` 改为读全局开关

## 行为语义（重要）

- **开** = `SCREEN_ORIENTATION_FULL_SENSOR`：跟随重力传感器自由旋转，**不受系统「自动旋转」总开关影响**。
- **关（默认）** = `SCREEN_ORIENTATION_PORTRAIT`：全局锁定竖屏。
- 阅读器内「屏幕方向」的显式「锁定竖屏 / 锁定横屏」优先级更高；只有「跟随系统」这一档服从全局开关。
- 离开阅读器时按全局开关恢复（原实现一律复位 `UNSPECIFIED`，会把「锁定竖屏」冲掉）。
- **默认 false**：不写任何配置时锁定竖屏；用户显式开启后才是重力感应。升级用户沿用其已存值（未写过 = 关）。

## 验证（全部实机/实跑）

| 项 | 结果 |
|---|---|
| 单测 | 136 tests / 0 failures / 0 errors / 2 skipped（WebDAV 环境语义） |
| `assembleRelease` | BUILD SUCCESSFUL |
| `lintDebug` | BUILD SUCCESSFUL，0 error / 21 warning |
| aapt | versionName=0.5.2，versionCode=26，含 INTERNET |
| apksigner | CN=XY Reader（正式签名，可覆盖升级） |
| 产物 | `outputs/XY-READER-0.5.2.apk`，md5 `b5311af3934b1549f05577b9166e5735` |

**MuMu 真机流程（2026-10-02 13:00–13:10）**

1. 装 0.5.2 → 设置页出现「显示 / 自动旋屏」（`checkable` 节点读数）。
2. 点开关 → `checked` 翻转（uiautomator 读数）。
3. 方向策略实测：关 = `mLastOrientation=1`（PORTRAIT），开 = `mLastOrientation=10`（FULL_SENSOR），**切换即时生效，无需重启**。
4. `am force-stop` + 重启 App → 设置页仍 `checked=false`，启动即 `mLastOrientation=1`（DataStore 落盘）。
5. 阅读配置管理 → 页面：`屏幕` 卡内 `自动旋屏` + `屏幕常亮` 均在位。
6. 阅读器半屏 → 页面：`阅读背景 / 图片缩放 / 自动旋屏 / 屏幕常亮`；此处点开关同样改变全局方向策略（`mLastOrientation` 1→10）。
7. 默认值（改为 false 后复验）：删掉设备上 `files/datastore/display_prefs.preferences_pb`（未写入状态）→ 冷启动 → 设置页开关为关、`mLastOrientation=1`；单测 `DisplayPrefsRoundTripTest` 同时断言「未写入 = false」。

**未验证（需真机手感）**：真实重力变化下的旋转跟随（模拟器无法造传感器输入）；MuMu 是虚拟 GPU，视觉结论方向性成立但真机需复核。

## 网页预览

`outputs/ui-preview/index.html`（gitignored，不入库）已同步：

- 设置页新增「显示」分组 + 开关；配置页「屏幕」卡与阅读器半屏页面 tab 同步。
- 顺手修掉两个**预览与 App 不一致的保真度缺陷**（用 CDP 量 DOM 确认，非本次引入）：
  - `.setting-title/.setting-subtitle` 是 inline，标题副标题挤在同一行（App 为两行）→ 加 `display:block`。
  - 底部悬浮导航在设置/详情/配置页也显示（App 的 `FloatingNavBar` 仅首页/书架）→ 改为同规则。
- 验证脚本：`scratch/xyreader-ui/verify_autorotate.mjs`、`verify_layout.mjs`（headless Chrome + CDP 真点击 + DOM 读回，`JS_ERRORS=[]`）。
