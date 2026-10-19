# 模拟器端到端验证：在线书源（搜索 → 抓取整本 → 上架）

日期：2026-10-18。设备：MuMu Nx（Android 15，1080×2400），包：release 签名 `versionCode 33 / 0.5.9`（原地升级，原有 3 本书与进度保留）。
数据源：**本机合成夹具**（`fixture_server.py`，只服务 `/search`、`/book/1`、`/book/1/toc`、`/book/1/c1`、`/book/1/c2`，全假内容）——**未访问任何真实站点**。

## 结论

**主链路已在真机环境跑通**：导入源 → 搜索（真实发出请求并解析）→ 抓取整本（详情/目录/两章）→ 入库上架。
**但通知展示与取消路径未验证**（抓取太快、`dumpsys notification` 在 MuMu 上段错误）。

## 已实测（按顺序）

| 步骤 | 证据 |
| --- | --- |
| 安装 | `adb install -r` Success；`versionCode=33 / versionName=0.5.9`，同签名原地升级，数据保留 |
| 新入口 | 设置页出现「在线书源 / 导入你自己的书源，搜索并抓取整本 / beta」 |
| 源管理页 | 标题、说明卡、导入源/搜索、`共 0 条源`、空态引导 全部正确 |
| 导入源 | 粘贴合成源 JSON → `共 1 条源，启用 1 条` + 卡片（LocalFixture / NOVEL / http://192.168.1.3:8765） |
| 搜索页 | `共 1 个可用源`、输入框、搜索按钮、提示文案正常 |
| **搜索** | 日志 `startSearch query=[test] sources=1 searchable=1 canSearch=[true]` → `results=1 books=1 firstError=null`；夹具收到 `GET /search?q=test 200` |
| 结果渲染 | `LocalFixture · 1 条` + `合成书一 / 甲 / 抓取整本` |
| **抓取整本** | 夹具依次收到 `GET /book/1`、`/book/1/toc`、`/book/1/c1`、`/book/1/c2`（均 200） |
| 通知权限 | 抓取期间弹出 Android 13+ 通知权限询问；**点允许后抓取继续**（拒绝也放行，符合设计） |
| **入库上架** | 设备库表：`4|合成书一|TXT|123`（前面是原有的 manga_normal/test4p/broken）——整本合成 TXT 已入库 |

## 本轮修掉的真机专属 bug（JVM 测不出来）

`LegadoRuleEvaluator.expand()` 的模板正则里，字面量 `}}` **未转义**：

- 桌面 JDK 容忍 `\{\{...\s*}}`，所以 `LegadoRuleEvaluatorTest.expandsTemplateVariables`、`LegadoSearchServiceTest.expandsKeywordIntoSearchUrl` **在 JVM 上全绿**；
- **Android 的 `java.util.regex` 直接抛 `PatternSyntaxException`**，导致 `searchOne()` 在拼搜索 URL 时就崩，请求根本没发出去；
- 真机 logcat（tag `XYBSearch`）给出：`Pattern.compile ← LegadoRuleEvaluator.expand ← LegadoSearchService.searchOne`，随后 `results=0 books=0`。

修复：右花括号改为 `\\}\\}`（即正则里的 `\}\}`）。修复后同一操作立即复现成功（见上表）。

**这是第二个"JVM 绿 ≠ 真机绿"的实例**（第一个是 Rhino 的 `java.beans.*` 导致的 R8 失败）。结论：**书源引擎这类"字符串→正则→解析"的代码，必须在设备上跑一次冒烟**，JVM 单测不足以证明。

## 同时改进

- 搜索页新增诊断日志（`adb logcat -s XYBSearch`）：打印 `query/sources/searchable/canSearch` 与 `results/books/firstError`；
- 提示文案拆分：「没有可用的源」只用于真的没源；「没搜到结果 / 源报错」单独提示（之前会把"搜不到"误报成"没有源"）；
- 搜索入口改为直接传**已过滤**的可搜索源（消除 UI 与服务双重过滤的口径差）。

## 未实测 / 已知缺口

- **前台服务通知的视觉与交互**：抓取在数秒内完成，通知一闪而过；`dumpsys notification` 在 MuMu 上段错误 → 通知栏展示、通知栏取消**未验证**；
- **取消路径**：未在设备上点过取消（单测覆盖了"取消即失败且不入库"）；
- 退后台继续抓取：未验证（需长任务/慢网络夹具）；
- 阅读新书：未在阅读器里打开 `合成书一`（123 B 的合成正文）；
- 真实源：**全程未访问任何真实站点**；真实源的规则复杂度、失败率、登录源均未触及；
- 版本号未递增、未 commit、未发版。

## 复现步骤

1. 本机起夹具：`python fixture_server.py`（端口 8765，绑定 0.0.0.0）；
2. 模拟器导入源（搜索页可达 `http://192.168.1.3:8765`）；
3. 设置 → 在线书源 → 去搜索 → 输入任意词 → 搜索 → 抓取整本；
4. 看 `adb logcat -s XYBSearch` 与夹具日志、`sqlite3 /data/data/com.xyreader/databases/ark_reader.db 'select id,title,format,size from books'`。
