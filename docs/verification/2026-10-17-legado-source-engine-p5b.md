# Legado 书源引擎 P5b 验证记录（界面入口）

日期：2026-10-17。基线：`564137b`。计划：`docs/superpowers/plans/2026-10-17-legado-source-engine.md`。
范围：**P5b = 源管理页 + 搜索页 + 设置入口 + 导航挂载**，把 P5a/P5c 的数据层接到界面上。

## 结论

**功能现在可以手动用了**（一条完整路径）：设置 → 在线书源（beta）→ 导入源（粘贴 JSON）→ 搜索 → 抓取整本 → 自动加入书架 → 用现有阅读器离线读。
仍需真机与真实源验证（本轮全部合成数据、未联网）。

## 已实测

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 编译 | `:app:compileDebugKotlin` | `BUILD SUCCESSFUL in 4m 59s` |
| 新界面专项 | `--tests "com.xyreader.ui.BookSourcesScreenTest"` | **3 项 0 失败**（一次通过）：空态/入口、坏 JSON 报错留在弹层、搜索页初始态 |
| 全量单元测试 | `:app:testDebugUnitTest --no-daemon --console=plain` | `BUILD SUCCESSFUL in 4m 31s`；**70 suite、492 项、0 failures、0 errors、4 skipped**（上一轮 69/489 → +1 suite、+3 项） |
| Lint | `:app:lintDebug --no-daemon --console=plain` | `BUILD SUCCESSFUL in 9m 47s`、`EXIT=0`；21 warnings、0 errors（与基线同数），新屏幕/设置页/导航 0 issue |

日志：`F:\AI-hub\agent\Hermes\cache\scratch\xy-reader-uitest\p5b-compile.log`、`p5b-ui.log`、`p5b-final.log`。

### 界面行为

**在线书源（源管理）**：`ui/BookSourcesScreen.kt`

- 顶部说明卡（源由用户导入、只存本机、抓取结果当本地书）；
- 「导入源」弹层：粘贴 JSON（或一键从剪贴板取）→ **解析失败留在弹层里报错**、不关窗不丢输入；
- 源列表：名称/类型/分组/URL + 启停开关 + 删除（二次确认，提示"已下载的书不受影响"）；需登录或不支持搜索的源在副标题里标注；
- 「搜索」按钮仅在存在"启用且可搜索"的源时可用；空态有引导文案。

**搜索书源**：`ui/BookSourceSearchScreen.kt`

- 关键词搜索（可搜索源数量提示）；结果**按来源分组**，每个源显示条数或失败原因（单源失败不影响其他源）；
- 每本书一行：书名/作者/分类 + 「抓取整本」按钮 → 抓取期间该行显示「抓取中 3/20」进度，完成后提示"已加入书架"；
- 进度状态放 `MutableStateFlow`（抓取回调在 IO 线程触发，直接改 Compose 状态不安全）。

**入口与路由**：`SettingsScreen` 新增「在线书源」（beta 徽标，仓库管理分组内）；`Navigation.kt` 新增两条路由 `settings/book-sources`、`settings/book-sources/search`。

### 数据层复用（未重复造）

界面只调用 P5a/P5c 已交付的能力：`LegadoSourceStore`（导入/启停/删除）、`LegadoSearchService`（多源搜索）、`LegadoBookDownloader`（整本抓取 → 走下载底座命名入库）。界面层不含任何解析或抓取逻辑。

## 未实测 / 未实现

- **真机与真实源**：界面只在 Robolectric 上跑过合成数据，**没有在手机上用真实源跑过端到端**；
- **未见到的界面行为**：真机字体/窄屏换行、超长书名的截断观感、抓取中切后台的表现（抓取是前台协程，切后台可能被系统回收——**这是已知缺口**，长任务需要前台服务或 WorkManager）；
- 结果分页/排序、书源分组筛选、批量抓取、断点续抓：未做；
- JS 规则（P4）：未开工（影响约 42% 的源）。

## 复现命令

```powershell
cd "F:\AI-hub\workroom\program\XY-reader"
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --tests "com.xyreader.ui.BookSourcesScreenTest"
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
& ./gradlew.bat :app:lintDebug --no-daemon --console=plain
```
