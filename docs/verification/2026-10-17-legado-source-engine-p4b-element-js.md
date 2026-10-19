# Legado 书源引擎 P4b 验证记录（JS 规则返回节点集合）

日期：2026-10-17。基线：P4（JS 沙箱）。计划：`docs/superpowers/plans/2026-10-17-legado-source-engine.md`。
范围：**P4b = 让 `@js:` 规则能返回节点集合**，使 `ruleSearch.bookList` / `ruleToc.chapterList` 可用 JS
（Legado 真实写法 `@js:java.getElementList('class.result@tag.li')`）。不引新依赖、不放宽沙箱、不改文本级 JS 行为。

## 一、设计：句柄数组（handle array）

问题：JS 沙箱里 `ClassShutter` 拒绝一切 Java 类，所以 jsoup 的 `Element` **不能直接进脚本**（否则等于把
一个 Java 对象暴露给脚本）。可 Legado 的 `bookList` 又必须让脚本交出「一批节点」。

做法：**节点留在宿主，脚本只拿序号**。

1. `JsBridge` 新增 `getElementList(rule): List<Element>`（`PageJsBridge` 用现有求值器在页面根上
   `base.elements(LegadoRule.parse(rule), root)` 实现，语义与 `getString` 一致，同样在「当前页面根节点」上求值）。
2. `LegadoJsRuntime` 在**每次求值**内建一个注册表 `MutableList<Element>`。`java.getElementList(rule)` 把节点
   逐个放进注册表，返回给脚本的是这些节点的**下标（句柄）**组成的 JS 数组。
3. 求值结束时检查脚本整条返回值：
   - 是 `NativeArray`、非空、且**每一项都是注册表范围内的整数句柄** → 还原成节点集合
     `JsResult.OkElements(elements)`；
   - 否则一律 `JsResult.Ok(字符串)`（Rhino 的数组按 JS 习惯连接）。
4. `LegadoRuleEvaluator.runJs` 把 `OkElements` 落成 `RuleValue.HtmlNodes`，于是 `elements(rule, root)`
   能拿到节点；搜索/目录链路**无需改动**（它们本来就用 `evaluator.elements(...)`）。

脚本侧能力：句柄数组是普通 JS 数组，可 `.slice` / `.filter` / `.map` / 取下标，脚本整条返回该数组即视为节点集合。
注册表是**单次求值内**的临时结构，求值结束即丢弃，不跨页面、不落盘。

### 为什么是「全部为合法句柄」才还原（安全/误判边界）

- 未调用 `getElementList` 时注册表为空 → 任何数组都不判为句柄，**文本级 JS 行为完全不变**
  （回归用例：`[1,2,3]` 仍得 `"1,2,3"`）。
- 句柄越界 / 混入非数字 / 空数组 → 一律退回文本，**不崩、不乱指节点**。
- 唯一可能的误判：脚本**既**调用了 `getElementList`、**又**主动返回一个纯数字且全在范围内的数组——
  这种写法在真实规则里不存在，且即便命中也是「脚本自己把句柄当值返回」，属可接受语义。

### 为什么注册表放在 `LegadoJsRuntime` 而不是 `JsBridge`

`getElementList` 返回的是「页面 → 节点」这一纯服务，桥不该关心 JS 值怎么编组；句柄编号与「返回值是否为句柄数组」
都属于 **JS 值编组**，与 Rhino 同生命周期（同一线程、同一次 `eval`），放沙箱里最内聚。桥因此保持无状态。

## 二、安全边界（未放宽）

- `ClassShutter` 仍拒绝一切 Java 类：节点走的是**句柄（整数）**通道，`Element` 本身不进脚本作用域，
  `typeof java.lang` / `java.io` / `Packages` / `javax` 仍全为 `undefined`，`new java.io.File('x')` 仍直接失败。
- 新增的只是 `java` 桥上的一个函数 `getElementList`，与 `getString`/`getStringList` 同级，**不引入任何类可见性**。
- 超时/越权/超指令数仍返回空并由上层诊断；文本级 JS 仍可用。

## 三、已实测

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 书源相关专项 | `:app:testDebugUnitTest --no-daemon --console=plain --tests "com.xyreader.booksource.*"` | **BUILD SUCCESSFUL in 3m 27s**、`EXIT=0`；`booksource` 8 个 suite **65 项、0 失败、0 错误** |
| 全量单元测试 | `:app:testDebugUnitTest --no-daemon --console=plain`（`--no-daemon`） | **BUILD SUCCESSFUL in 4m 8s**、`EXIT=0`；**72 suite、505 项、0 failures、0 errors、4 skipped**（P4 为 71/498 → +1 suite、+7 项） |

日志：`F:\AI-hub\agent\Hermes\cache\scratch\xy-jsel-p4b-1.log`（专项）、`xy-jsel-p4b-full.log`（全量）。

### 覆盖面（节点集合相关的行为，全部合成夹具、不联网）

- **句柄 → 节点**：`java.getElementList('...')` 直接返回 → `JsResult.OkElements`，节点文本/属性正确
  （`LegadoJsRuntimeTest.getElementListReturnsHandlesResolvedToNodes`）；
- **脚本层可加工**：`java.getElementList('x').slice(1)`（先切再返回）仍还原成节点集合；
- **越界/非句柄不崩**：`java.getElementList('x'); [0, 99]` → 退回文本 `"0,99"`；混入字符串 → 退回文本；
  空数组 → `""`（`nonHandleArraysAndOutOfRangeHandlesFallBackToText`）；
- **求值级**（`LegadoJsElementListTest`）：`@js:java.getElementList('class.result@tag.li')` →
  `RuleValue.HtmlNodes(2)`；拿到节点后 `class.title@text` / `class.title@href` 在单节点作用域内照常求值；
  `.slice(1)` 少一个节点；无匹配 → 空；
- **文本级 JS 不变**：`@js:java.getString('class.title@text')` → 文本；`@js:[1,2].length` → `"2"`；`[1,2,3]` → `"1,2,3"`；
- **搜索集成**（`LegadoSearchServiceTest.jsElementListBookListYieldsBooks`）：
  `bookList = "@js:java.getElementList('class.result@tag.li')"`、其余规则 css → 返回书名/作者/链接，
  相对链接补全为绝对地址；**既有的 JS 边界用例仍绿**：
  `jsOnlyRulesYieldNoBooksRatherThanFakeResults`（`bookList` 用 `java.getString` 返回文本而非节点集合 → 仍报「没匹配到」）。

## 四、未实测 / 已知缺口

- **节点上的方法仍不可用**：脚本拿到的句柄是整数，**不能** `element.text()` / `.attr()` / `.select()`
  （那需要把 jsoup 类型暴露给脚本，违背沙箱）。因此真实源里「取到节点列表后再在 JS 里逐个加工」的写法
  （`getElementList(...).map(e => e.text())`）**仍不支持**——此时脚本应改用 `java.getString`，
  或把逐节点加工交给 CSS 规则（`bookList` 用 JS 定位、其余字段用 css，正是本次集成用例覆盖的形态）。
  真正要支持节点级方法，需要给每个句柄包一层「只有少数只读方法」的 JS 对象（仍不暴露 Java 类），属后续增量。
- `java.getElementList` 内部规则**不再递归执行 `@js:`**（与 `java.getString` 一致，桥用的是无 JS 的求值器）；
- 未在 release 构建（R8/Minify）下跑过：节点集合路径新增的 `NativeArray` 分支只在 debug 单测里验证过；
- 真机未验证；
- `LegadoContentService.chapters()`（`ruleToc.chapterList` 用 JS）走的是同一套 `evaluator.elements(...)`，
  逻辑上同样受益，但**未单独加 JS 目录的集成用例**（本轮只加了搜索侧）；如需可后续补一条合成目录夹具。

## 五、复现命令

```powershell
cd "F:\AI-hub\workroom\program\XY-reader"
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --tests "com.xyreader.booksource.*"
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```
