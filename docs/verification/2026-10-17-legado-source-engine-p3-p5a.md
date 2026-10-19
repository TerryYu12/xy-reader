# Legado 书源引擎 P3 + P5a 验证记录（XPath / 源仓库 / 多源搜索）

日期：2026-10-17。基线：`564137b`。计划：`docs/superpowers/plans/2026-10-17-legado-source-engine.md`。
范围：**P3 = XPath 求值**（master 已批；实际**未新增依赖**，用 Android/Java 自带的 `javax.xml.xpath`）；**P5a = 源仓库 + 多源搜索**（数据层）。另：按 master 要求加入 QQ 交流群 1042819678。

## 结论

引擎的「能取到值」这一步打通了：给一批源 + 一个关键词，能真的发起请求、按规则解析出书名/作者/链接，并且单源失败不影响其他源。**但还不能在界面上用**（P5b 的 UI 未做），也还不能读正文/下载整本（P5c 未做）。

## 已实测

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| booksource 专项（4 个 suite） | `--tests "com.xyreader.booksource.*"` | **41 项 0 失败**：解析 10 / 求值 18 / 源仓库 5 / 搜索 8（`BUILD SUCCESSFUL in 3m 21s`） |
| 全量单元测试 | `:app:testDebugUnitTest --no-daemon --console=plain` | `BUILD SUCCESSFUL in 3m 29s`；**67 suite、478 项、0 failures、0 errors、4 skipped**（上一轮 65/462 → +2 suite、+16 项） |
| Lint | `:app:lintDebug --no-daemon --console=plain` | `BUILD SUCCESSFUL in 4m 25s`；21 warnings、0 errors（与基线同数）；**booksource 与 SupportScreen 均 0 issue** |

日志：`F:\AI-hub\agent\Hermes\cache\scratch\xy-reader-uitest\legado-p3c.log`（P3）、`legado-p5a3.log`（P5a 专项）、`legado-p5a-final.log`（全量+lint）。

### P3：XPath

- `//…` 与 `@XPath:` 两种写法；支持元素、属性节点、文本节点（`/text()`）、谓词、下标（`li[1]`）、`contains(@class,…)`；
- **`@` 歧义处理**：XPath 的属性步 `/a/@href` 与本引擎的 `@` 串联标记同形——策略是**先整条当 XPath 编译求值，失败再剥离尾部 `@` 段当串联操作**（`//ul/li/a/@href` 走前者，`//li//a@href` 走后者）；
- HTML → XML 再解析（`<br>` 等空元素必须闭合，否则整条 XPath 失败——已有专门用例覆盖）；
- **XXE 防护**：禁 DTD、禁外部实体；
- 表达式非法/无匹配一律返回空，不抛。
- 已知代价：HTML 经 XML 往返可能对畸形标记失真；XPath 只占实测规则的 0.8%，可接受。

### P5a：源仓库（`LegadoSourceStore`）

- DataStore 单键 JSON，**不新增 Room 表**（避免数据库迁移，与下载底座的取舍一致）；
- 身份 = `bookSourceUrl`：导入去重、重复导入**更新规则但保留用户本机的启停选择**；
- 增删改查（导入/删除/启停/清空）+ 规则字段往返存储；
- **仓库仍不含任何源数据**，存的都是用户自己导入的。

### P5a：多源搜索（`LegadoSearchService`）

- 只搜「启用 + 可搜索（有 `searchUrl` 与 `bookList`）」，其余跳过**且不发请求**；
- `{{key}}` 等模板展开（关键词做 URL 编码）；源自己的 `header`（JSON）按约定转发（UA/Referer）；
- 每源独立超时（默认 15s）与错误隔离：一个源失败只记 `error`，不影响其他源；
- 结果条目**缺书名或缺链接即丢弃**（半截数据不入结果）；相对链接补全成绝对地址；
- 规则是 JS（P4 未实现）时如实报「没匹配到规则」，**不编造结果**。

### 顺带：QQ 交流群

- 应用「设置 → 支持作者」页底部新增**交流群卡片**，点击复制群号 `1042819678`（不改跳转，避免部分 ROM 拦 QQ 链接）；
- `README.md`（## 交流）与 `README_EN.md`（## Community）同步写上；README 原有的「不提供、不内置、不托管任何书源」免责声明与本条主线一致，未改动。

## 开发中由测试暴露的 3 个问题（记录在案）

1. **`/a/@href` 被 `@` 串联切分切坏**（XPath 属性步与串联标记同形）→ 改成「整条优先、尾段回退」，并给切分器加了方括号感知；
2. **`withTimeoutOrNull` 返回 `Pair` 被推断成 `Serializable`** → 换成明确类型的 `PageOutcome`；
3. **DataStore 委托是进程级单例**，同 JVM 内多个用例共享数据导致互相污染 → 测试 `@Before` 显式清空（生产行为不变，这个坑写进了测试注释）。

## 未实测 / 未实现

- **UI（P5b）**：源管理页（导入/粘贴/启停/删除）、搜索页、结果列表、下载/加入书架入口——都还没有，所以「用起来」还差一步；
- **正文链路（P5c）**：书籍详情、目录（`ruleToc`）、正文抓取（`ruleContent`，含 `nextContentUrl` 多页拼接）、在线阅读与整本下载入库；
- **JS 规则（P4）**：已批未开工，影响约 42% 的源；
- **真机与真实源**：全部夹具是合成 HTML/JSON；**没有对任何真实站点发起过请求**（本轮仍未联网取源页面）；
- 未做并发限速（实测分布里 `concurrentRate` 字段存在，多源并发策略留到 UI 阶段一并定）。

## 复现命令

```powershell
cd "F:\AI-hub\workroom\program\XY-reader"
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --tests "com.xyreader.booksource.*"
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
& ./gradlew.bat :app:lintDebug --no-daemon --console=plain
```
