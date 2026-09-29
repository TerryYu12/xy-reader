# 0.3.5 书架页「开始阅读」菜单（与首页同款，全域右下角播放键）— 验证记录（2026-09-29）

## 需求来源

承接 0.3.4（首页右下角改为播放键 + 四选项菜单）。按 MH-ARK 参考图（其播放菜单出现在书架 tab），
把书架页右下角同样换成「开始阅读」菜单，实现全应用右下角统一为播放键。

## 实现

| 文件 | 改动 |
|---|---|
| `ui/ShelfScreen.kt` | 右下角扫描 FAB → `QuickReadMenuOverlay`（与首页同款组件）；新增 `onOpenReader` 参数；书架页无分类筛选，四项均作用于全库 |
| `ui/Navigation.kt` | ShelfScreen 接入 `onOpenReader`（`reader/{id}?page=0` 续读） |
| `ui/HomeScreen.kt` | 空书架文案改指「书架」页**右上角** +（右下角已是播放键） |

导入入口不丢失：书架页右上角「添加仓库」（+图标）按钮保留原功能；设置 → 仓库管理入口不变。

## 验证

- 单测 **47/47 全绿**（组件级：`QuickReadMenuTest` 2 条覆盖菜单展开/收起/回调；`QuickReadTest` 3 条覆盖选取逻辑；其余 42 条全量无回归）。
- lint：0 errors。
- APK 抽检：aapt 核版本 `0.3.5 / versionCode 10`；debug/release dex 含菜单与新文案符号。
- md5：build 与 outputs 拷贝一致（见下）。

### 诚实声明

书架页的接线为编译级验证 + 组件行为测试；**两页在真机上的视觉/交互一致性**需实机复核。

## 实机清单（用户执行）

1. 书架页右下角应为播放键（不再是 +）；点开四选项 + 关闭键，交互与首页一致；
2. 书架页四个选项均直接进阅读器（该页无分类，四项=全库范围）；
3. 书架页右上角 + 仍可导入文件夹（导入入口）；
4. 首页 ↔ 书架来回切换，两处播放菜单均正常。

## 产物

- `outputs/app-release-0.3.5.apk`（22.3MB，md5 `8f9419907797aa20ef7eadfadcc18aaa`）、
  `outputs/app-debug.apk`（40MB，md5 `57444397330bb036672ee780aca5d918`）。
- 抽检记录：debug/release dex 均含 `QuickReadMenuOverlay` / `当前书架上次阅读` / `开始阅读` /
  `还没有任何阅读记录` / `点「书架」页右上角 + 导入`。
- lint：0 errors（19 既有 warnings）。
- 回滚点：`backups/`（本版改动：ShelfScreen 结构小幅重构 + Navigation 一行 + HomeScreen 文案一行）。
