# 0.3.4 主页「开始阅读」菜单（右下角播放键 + 四选项悬浮菜单）— 验证记录（2026-09-29）

## 需求

> 「1是目前软件的主页，2是mh-ark的主页，3是mh-ark点击右下角播放按键之后的界面，
> 我需要咱的软件右下角那个不是加号，而是播放按键。按了之后出现界面差不多，而且功能要都有」

## 实现

| 文件 | 改动 |
|---|---|
| `ui/QuickReadMenu.kt`（新增） | `QuickReadMenuOverlay`：右下角 FAB + 悬浮胶囊菜单。收起=播放键；展开=四颗彩色胶囊（上浮淡入）+ 半透明遮罩（点击/返回键收起），FAB 变关闭键 |
| `core/QuickRead.kt`（新增） | 选取逻辑纯函数：`lastRead`（lastReadAt 最大）、`random`（可注入种子） |
| `ui/HomeScreen.kt` | 去掉右下角 + 扫描 FAB → 换成「开始阅读」菜单；四个动作接选取逻辑与兜底提示；空书架文案改指「书架」页的 + |
| `ui/Navigation.kt` | 新增 `onOpenReader`：四个动作均直达阅读器并**续读已存进度**（`reader/{id}?page=0`，不覆盖进度） |

菜单四选项与行为（作用于上方当前所选分类）：

| 选项 | 行为 | 空数据兜底 |
|---|---|---|
| 当前书架上次阅读 | 当前分类内最近阅读的书 → 阅读器续读 | 「当前分类是空的 / 还没有阅读记录」 |
| 上次阅读 | 全库最近阅读 → 阅读器续读 | 「还没有任何阅读记录」 |
| 当前书架随机 | 当前分类内随机一本 → 阅读器 | 「当前分类是空的」 |
| 随机 | 全库随机一本 → 阅读器 | 「书架空空如也，先导入一些书吧」 |

设计说明：结构对齐 MH-ARK（播放键↔关闭键 + 悬浮胶囊 + 遮罩），视觉沿用本应用 M3 语言
（胶囊=surfaceContainerHigh + 彩色圆标；颜色取主题点缀色板 紫/橙/绿/青）。导入功能保留在
「书架」页右下角 +（本次未动），主页空书架文案已同步指引。

## 验证

- 单测 **47/47 全绿**，其中新增：
  - `QuickReadTest`（3 条）：lastRead 取最大/无记录 null/random 空集 null 且总在范围内；
  - `QuickReadMenuTest`（2 条，真实 Compose 运行时）：点播放键展开四选项、再点关闭键收起；
    点「当前书架随机」回调对应动作。
- 既有 42 条全部无回归（含阅读弹层/配置页胶囊、小说排版、阅读器链路）。
- icon 可用性预核（aar 内类名）：filled.PlayArrow / outlined.Close（core）、
  outlined.Autorenew / History / Shuffle / Casino（extended）全部实存。
- lint：0 errors（新增文件零告警）。
- APK 抽检：aapt 核版本 `0.3.4 / versionCode 9`；debug/release dex 含
  `QuickReadMenuOverlay`、`当前书架上次阅读`、`开始阅读`、`书架空空如也` 等符号。
- md5：build 与 outputs 拷贝一致（见下）。

## 实机清单（用户执行）

1. 主页右下角应为**播放键**（不再是 +）；点开出现四颗胶囊菜单 + 关闭键；
2. 分别点：当前书架上次阅读（先切到某分类再试）、上次阅读、两个随机——都应直接打开阅读器；
3. 空分类/无记录时应有气泡提示（当前分类是空的 / 还没有任何阅读记录）；
4. 遮罩点击、返回键、关闭键三种方式都能收起菜单；
5. 导入入口在「书架」页右下角 +（保留验证一下仍在）。

## 产物

- `outputs/app-release-0.3.4.apk`（22.3MB，md5 `f0dd95f5fe5c0d03fd6894188162190f`）、
  `outputs/app-debug.apk`（40MB，md5 `357a5232b988472285720ad9ed392fe5`）。
- 抽检记录：debug/release dex 均含 `QuickReadMenuOverlay` / `当前书架上次阅读` / `开始阅读` /
  `书架空空如也` / `当前分类是空的` / `QuickReadKind`。
- lint：0 errors（19 既有 warnings）；icon 预核：core=PlayArrow/Close、extended=Autorenew/History/Shuffle/Casino。
- 回滚点：`backups/`（本版改动：HomeScreen 结构小幅重构 + 两个新文件 + Navigation 一行）。
