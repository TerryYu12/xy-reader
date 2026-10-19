# Legado 书源引擎 P5c 验证记录（正文链路 + 入库闭环）

日期：2026-10-17。基线：`564137b`。计划：`docs/superpowers/plans/2026-10-17-legado-source-engine.md`。
范围：**P5c = 详情 → 目录（含翻页）→ 正文（含多页拼接与净化）→ 合成可读文件 → 走下载底座命名入库上架**。
另：本轮按 master 要求加了 QQ 交流群（见 `...-p3-p5a.md`）。

## 结论

**数据层整条链路打通**：给定用户导入的源与关键词，可以做到「搜索 → 取详情/目录 → 逐章抓正文 → 合成 TXT → 入库 → 用现有阅读器离线读」。
**还差 UI 入口（P5b）**，所以现在仍不能在手机上手动操作；也未对任何真实站点联网取过数据。

## 已实测

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| booksource 专项（6 个 suite） | `--tests "com.xyreader.booksource.*"` | **52 项 0 失败**：解析 10 / 求值 18 / 源仓库 5 / 搜索 8 / 正文链路 7 / 整本入库 4 |
| 全量单元测试 | `:app:testDebugUnitTest --no-daemon --console=plain` | `BUILD SUCCESSFUL in 5m 10s`；**69 suite、489 项、0 failures、0 errors、4 skipped**（上一轮 67/478 → +2 suite、+11 项） |
| Lint | `:app:lintDebug --no-daemon --console=plain` | 0 errors、21 warnings（与基线同数）；本轮最后一次改动只碰测试文件，主线源码自那次 lint 后未变 |

日志：`F:\AI-hub\agent\Hermes\cache\scratch\xy-reader-uitest\legado-p5c3.log`、`legado-p5c-final*.log`。

### `LegadoContentService`（目录 / 正文 / 整本）

- **目录**：详情页 →（可选 `ruleBookInfo.tocUrl`）→ `ruleToc.chapterList`，按 `nextTocUrl` 翻页（上限 10 页，**访问过的页不再重入**，防自指死循环）；
- **正文**：`ruleContent.content` + 按 `nextContentUrl` 拼接后续页（上限 10 页）；命中整段 HTML 时自动剥离标签；再按 `replaceRegex` 净化；
- **整本**：目录 + 逐章 → 合成 TXT（书名/作者/章节名/正文顺序写入）；`onProgress(done,total)` 供 UI 显示进度。

### `LegadoBookDownloader`（入库闭环）

- 抓取写在临时文件 → 通过后交 `importDownloadedFile` 入库 → 删临时文件；
- **命名沿用下载底座**（来源 ID + 条目 ID + 版本 + 身份哈希）：同一本书重复下载同名、入库幂等；不同来源同名书不互相覆盖（有专门用例）。

### 硬约定（都落在测试里）

- **不产出半本书**：任何一章失败即整本失败，且**不留文件、不入库**（错误信息指明第几章，如「第 2/2 章」）；
- 目录为空 / 正文为空 → 明确报错，不产出空书；
- 全部失败路径都不留临时文件。

## 真实语料纠正了一次实现错误（记录在案）

我最初按「`正则##替换` 成对切分」实现 `replaceRegex`，测试立刻失败。随后**用真实语料核对**（552 条含 `replaceRegex` 的真实源）：

| 形态 | 条数 | 正确语义 |
| --- | --- | --- |
| `##正则`（以 `##` 开头、单个正则） | **519** | 删除匹配内容 |
| `##正则##替换` | 22 | 替换 |
| JS 形态（`@js:` / `<js>`） | 11 | 本阶段跳过（P4） |

多条正则是在**同一条正则里用 `|` 并联**，不是用 `##` 分隔。实现已按此修正，并补了「替换形态」与「JS 形态原样保留」两个用例。**这是"先写测试 + 用真实数据核对"救回来的第二处语义错误。**

（分析脚本与原始语料在本机临时目录，不入库。）

## 未实测 / 未实现

- **UI（P5b）**：源管理页、搜索页、结果列表、下载入口——都没做，所以还不能手动用；
- **JS 规则（P4）**：已批未开工（影响约 42% 的源，且 `replaceRegex` 的 JS 形态同样跳过）；
- **真实站点**：全程合成页面，**未联网取过任何真实源数据**；
- 并发/限速（`concurrentRate`）、断点续抓（抓一半中断后从第几章续）、章节内图片（`imageStyle`）——均未做；
- 真机：无。

## 复现命令

```powershell
cd "F:\AI-hub\workroom\program\XY-reader"
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --tests "com.xyreader.booksource.*"
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
& ./gradlew.bat :app:lintDebug --no-daemon --console=plain
```
