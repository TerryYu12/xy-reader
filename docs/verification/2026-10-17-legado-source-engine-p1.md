# Legado 书源引擎 P1 验证记录

日期：2026-10-17。基线：`564137b`。计划：`docs/superpowers/plans/2026-10-17-legado-source-engine.md`。
范围：**P1 = 书源模型 + 规则串解析**（无求值、无 UI、无网络）。主仓零源数据。

## 结论

P1 代码与自动化验证通过。**规则「求值」尚未实现**，所以现在还不能真正搜到/读到一本书——这是分期内正常状态，不是缺陷。

## 已实测

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 新增 P1 测试 | `--tests "com.xyreader.booksource.*"` | `LegadoSourceParsingTest` **10 项，0 失败**（`BUILD SUCCESSFUL in 6m 15s`，含编译） |
| 全量单元测试 | `:app:testDebugUnitTest --no-daemon --console=plain` | `BUILD SUCCESSFUL in 2m 59s`；**64 suite、447 项、0 failures、0 errors、4 skipped**（上一轮 63/437 → +1 suite、+10 项） |
| Lint | `:app:lintDebug --no-daemon --console=plain` | `BUILD SUCCESSFUL in 5m 4s`；21 warnings、0 errors（与基线同数）；**新增文件 0 issue** |

日志：`F:\AI-hub\agent\Hermes\cache\scratch\xy-reader-uitest\legado-source-p1.log`（专项）、`legado-p1-final.log`（全量+lint）。

### 覆盖的行为

规则解析（`LegadoRule`）：

- 前缀识别：`@css:` / `@XPath:` / `//` → XPath / `@json:` / `$.` → JSONPath / `@regex:` / 无前缀 → 默认选择器语法；前缀只剥标记，表达式原样保留；
- 回退链 `||`、串联链 `&&`、交错 `%%`（标记）三类组合切分；
- `##` 替换段：`表达式##正则##替换` 取三段；**两段式（`表达式##正则`）= 删除匹配**（替换为空串），无 `##` 时为 null；
- **JS 规则（`@js:` / `<js>`）不切分操作符**（JS 里 `||`、`&&`、`##` 是常态），并置 `usesJs` 标记，导入时可提示「该规则暂不支持」；
- 顶层切分跳过 `{{ }}` 插值、单/双引号字符串与反斜杠转义（`[data-key='p||q']` 不被切断）；`{{ }}` 置 `usesTemplate`；
- 空/空白规则 → `isEmpty`，不抛异常。

书源模型（`LegadoBookSource`）：

- 数组与单对象两种导入形态（Legado 导入既可能是一条也可能是一批）；非 JSON 文本返回空列表而不抛；
- 按实测字段映射：类型/分组/备注/`enabled`/`header`/`loginUrl`/`searchUrl`/`exploreUrl`/`concurrentRate` + 四个规则组（`ruleSearch`/`ruleBookInfo`/`ruleToc`/`ruleContent`）全子字段；
- **缺 `enabled` 视为启用**（多数源不写该字段）；坏条目跳过、不拖垮整批导入；
- 未知 `bookSourceType` 落到 `UNKNOWN`（不猜）；`canSearch`（有无 searchUrl + bookList）、`requiresLogin` 派生属性；
- `hostKey` 取域名（用于下载身份，避免源 URL 路径变化导致身份漂移）。

全部测试使用**合成源**（`example.com`），仓库不含任何真实站点数据。

## 未实测 / 未实现

- **规则求值**：CSS/默认选择器、JSONPath、正则、模板、XPath 全部未实现 → 现在无法真正读出书名/目录/正文；
- **JS 规则**：不执行（P4）；
- 网络层（取搜索页/目录/正文、多页 `nextContentUrl` 拼接）、源管理 UI、搜索/下载 UI：未开始；
- 真机：无。

## 已知缺口

- `%%` 交错在 P1 只标记不区分语义（实测 46898 条规则里仅 10 条使用，P2 再定）。
- `ruleExplore` / `ruleReview` / `bookUrlPattern` / `coverDecodeJs` / `jsLib` 等字段未建模（保留在 `raw` 里，避免格式演进丢数据）。
- 本文件只覆盖 P1；P2 起需要新依赖（见计划文档「待 Master 批准」）。

## 复现命令

```powershell
cd "F:\AI-hub\workroom\program\XY-reader"
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --tests "com.xyreader.booksource.*"
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
& ./gradlew.bat :app:lintDebug --no-daemon --console=plain
```
