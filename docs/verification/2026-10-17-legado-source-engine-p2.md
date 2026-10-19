# Legado 书源引擎 P2 验证记录（规则求值）

日期：2026-10-17。基线：`564137b`。计划：`docs/superpowers/plans/2026-10-17-legado-source-engine.md`。
范围：**P2 = 规则求值子集**（HTML/默认选择器语法、JSONPath 子集、正则、`##` 替换、模板展开、回退/串联）。新增依赖：`org.jsoup:jsoup:1.23.2`（Apache-2.0，已获 master 批准）。

同时交付：Z-Library 适配器**私人仓库** `TerryYu12/xy-reader-zlib-source`（详见末节）。

## 结论

求值层已能在合成 HTML/JSON 上正确取值，主仓仍**零源数据**。XPath（P3）与 JS（P4）仍未实现——遇到这两类规则一律返回空，绝不假装成功。

## 已实测

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 求值专项 | `--tests "com.xyreader.booksource.*"` | `LegadoRuleEvaluatorTest` **15 项 0 失败** + `LegadoSourceParsingTest` 10 项 0 失败（`BUILD SUCCESSFUL in 4m 50s`） |
| 全量单元测试 | `:app:testDebugUnitTest --no-daemon --console=plain` | `BUILD SUCCESSFUL in 1m 34s`；**65 suite、462 项、0 failures、0 errors、4 skipped**（上一轮 64/447 → +1 suite、+15 项） |
| Lint | `:app:lintDebug --no-daemon --console=plain` | `BUILD SUCCESSFUL in 2m 56s`；21 warnings、0 errors（与基线同数）；**新增文件 0 issue** |

日志：`F:\AI-hub\agent\Hermes\cache\scratch\xy-reader-uitest\legado-p2b.log`（专项）、`legado-p2-final.log`（全量+lint）。

### 覆盖的行为（`LegadoRuleEvaluator`）

- **默认选择器语法**：`class.X` / `tag.X` / `id.X` 选节点，`text` / `ownText` / `textNodes` 取文本，`html` 取整段 HTML，其它裸词当属性名（`href`/`src`…），`abs:` 前缀取绝对地址；尾索引 `.0` / `.-1`（越界返回空，不抛）；
- **`@css:`**：CSS 选择器，且**后面仍可继续串接取值操作**（`@css:ul.list > li@tag.a@href`）；
- **链中途切换求值方式**：`class.author@text@regex:作者(.+)`、`…@json:$.list[*]`（Legado 规则的常见写法）；
- **JSONPath 子集**：`$.a.b`、`$.list[0].name`、`$.list[*].name`、`$['a']['b'][*]['id']`；字符串原样、对象/数组转 JSON 文本；
- **`@regex:`**：取第一个捕获组（无捕获组取整体匹配）；
- **组合语义**：`||` 回退（取第一个非空替代项）、`&&` 串联、`##` 替换（两段式=删除匹配）；
- **模板展开**：`{{baseUrl}}`、`{{key}}`；未知变量**原样保留**（不静默吞掉，便于排障）；
- 取不到一律返回空、不抛异常（一条源写错不该让整次搜索崩掉）。

### 开发中发现并修掉的两个语义错误（记录在案）

1. `$['a']...` 被 `kindOf` 误判为默认选择器语法（只认了 `$.`）→ 已改为任何 `$` 开头都算 JSONPath；
2. 链尾 `@regex:` 未被识别（`class.author@text@regex:…` 里 `regex:` 被当成属性名）→ 已支持链中途按 `regex:`/`json:`/`css:` 切换求值方式；`xpath:`/`js:` 中途出现则明确返回空。

这两条都是**先写测试才暴露的**（测试里各有一条对应断言），不是事后补的说明。

全部夹具为**合成 HTML/JSON**（`example.com`、合成书名），仓库不含任何真实站点页面或响应。

## 未实测 / 未实现

- **`@XPath:`（P3）**、**`@js:` / `<js>`（P4）**：显式返回空；
- 默认语法的进阶写法未支持：排除项 `!class.x`、索引区间 `N:M`、`children`/`parent`/`sibling` 等结构操作、`textNodes` 的精确空白语义；
- **网络与端到端**：还没接「取搜索页 → 求值 → 得到书列表」的链路；没有源管理/搜索/下载 UI；真机未验证；
- 覆盖率预期仍按实测分布：不实现 JS 时约覆盖 58% 的源（JS 只占 6% 的规则，但集中在部分源上）。

## 顺带交付：Z-Library 适配器私人仓库

`TerryYu12/xy-reader-zlib-source`（**private，已核 `isPrivate: true`**）：

- Kotlin/JVM 模块，传输层抽象 + 假实现测试，**测试不联网、不含真实抓包**；
- **16 项测试 0 失败**（`BUILD SUCCESSFUL in 2m 38s`）；
- 已实现且**已实测**的接口：活域列表、匿名搜索、单书详情；失败体 `{"success":0,"error":…}` 的分类；
- **未实测部分显式失败**：登录成功响应体字段未核对（取不到凭据就明确报错）、下载直链未实现（`docs/DESIGN.md` 里写了补全步骤，需用真实账号）；
- 仓库不含账号/密码/Cookie/个人域，`.gitignore` 已拦凭据类文件名。

## 复现命令

```powershell
cd "F:\AI-hub\workroom\program\XY-reader"
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --tests "com.xyreader.booksource.*"
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
& ./gradlew.bat :app:lintDebug --no-daemon --console=plain

cd "F:\AI-hub\workroom\program\xy-reader-zlib-source"
& ./gradlew.bat test --no-daemon --console=plain
```
