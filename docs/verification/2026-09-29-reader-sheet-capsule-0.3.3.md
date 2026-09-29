# 0.3.3 阅读设置弹层改版：胶囊分组 + 横向分页（2026-09-29）

## 需求与背景（含一次诊断纠偏）

用户两次反馈「阅读设置页的『左右胶囊切换』没有落实」，并附截图。经截图取证确认：
**用户指的是「阅读界面内拉起的设置快捷面板」**（翻页模式/阅读背景/图片缩放/小说字体 竖排列表），
而不是设置菜单里的「阅读配置管理」独立页——此前胶囊分页工作做进了后者（0.3.0/0.3.1），
**弹层一直没动**，入口判断失误是两次反馈的共同根因。

## 实现

| 文件 | 改动 |
|---|---|
| `reader/ReaderScreen.kt` | `ReaderSettingsSheet` 重写：**顶部胶囊（翻页模式 / 页面 / 字体）+ 固定高 HorizontalPager（340dp，页内可滚）+ 底部提示行**；内容拆出 `ReaderSettingsSheetContent`（internal，独立于 ModalBottomSheet 容器，便于 UI 测试直挂） |
| `ui/Common.kt` | `CapsuleTab` 抽为公共组件（配置页与弹层共用；Surface(onClick) 涟漪反馈） |
| `ui/ReaderConfigScreen.kt` | 删除私有 CapsuleTab 副本，改从 Common 共用 |

分组内容映射（原有控件全部保留、行为不变）：

- **翻页模式**：左右翻页 / 上下滚动 chips
- **页面**：阅读背景 4 档圆色块 + 图片缩放 2 档 + 屏幕常亮开关
- **字体**：小说字体 chips（系统族/内置/导入）+ 字重 + 字号滑杆 + 首行缩进开关 + 说明文案

交互：点胶囊 `animateScrollToPage` 切组；横滑分页时胶囊选中态跟随（`pagerState.currentPage`）。

## 验证

- 单测 **42/42 全绿**，其中新增 `ReaderSheetCapsuleTest` 2 条（真实 Compose 运行时）：
  - `弹层点胶囊切换分组`：初始翻页模式组 → 点「字体」→出现首行缩进 → 点「页面」→出现图片缩放 → 点回「翻页模式」往返全通；
  - `弹层内容区横滑切换分组`：左滑到页面组、右滑回翻页模式组。
  - 既有 `ReaderConfigCapsuleTest`（配置页 2 条）仍全绿——公共 CapsuleTab 重构无回归。
- lint：0 errors。
- APK 抽检：aapt 核版本 `0.3.3 / versionCode 8`；dex 字符串含 `ReaderSettingsSheetContent`、
  `更多设置：设置-阅读配置管理`（debug 包类名 + 中文字面量；release 搜字符串）。
- md5：build 与 outputs 拷贝一致（debug `a989a3f6…`、release `8a3758e0…`）。

### 诚实声明

Robolectric 验证的是**行为链路**（点胶囊/横滑 → 分组切换生效）；胶囊样式、340dp 分页区在真机上的
视觉观感需真机复核（本机无设备/模拟器）。

## 实机清单（用户执行）

1. 阅读界面拉出底部设置面板：顶部应有三颗胶囊「翻页模式 / 页面 / 字体」；
2. 点三颗胶囊往返切换、内容区直接横滑——两者双向同步；
3. 组内控件逐个回归：翻页模式切换、背景 4 色、图片缩放、字体 chips、字重、字号、首行缩进、屏幕常亮；
4. 底部「更多设置：设置-阅读配置管理」提示行在导航栏之上完整可见（此前截图字区被导航栏遮挡）。

## 产物

- `outputs/app-release-0.3.3.apk`（22.3MB，md5 `8a3758e035eb8eb638feb2e5d978a590`）、
  `outputs/app-debug.apk`（40MB，md5 `a989a3f670512fe069fedb9fbdff94fe`）。
- 抽检记录：debug/release dex 均含 `ReaderSettingsSheetContent` / `SheetPagerHeight` /
  `更多设置：设置-阅读配置管理` / `翻页模式` / `首行缩进` / `CapsuleTab`。
- 回滚点：`backups/`（本版改动集中于 ReaderScreen.kt 弹层块 + Common.kt，配置页仅删私有副本）。
