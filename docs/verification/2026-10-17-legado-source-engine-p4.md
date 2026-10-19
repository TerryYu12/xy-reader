# Legado 书源引擎 P4 验证记录（JS 规则沙箱）

日期：2026-10-17。基线：`564137b`。计划：`docs/superpowers/plans/2026-10-17-legado-source-engine.md`。
范围：**P4 = JS 规则执行**（Rhino 沙箱 + `java.*` 桥 + 接入规则求值/搜索/正文链路）。新增依赖：`org.mozilla:rhino:1.7.15`（master 已批）。

## 结论

JS 规则**能跑了**，且带两条硬边界：**脚本看不到任何 Java 类**、**超时会失败并停用 JS 规则**。
但**元素级返回**（如 `bookList` 用 JS 返回节点集合）本阶段仍不支持——那是下一刀。

## 已实测

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| JS 沙箱专项 | `--tests "com.xyreader.booksource.LegadoJsRuntimeTest"` | **6 项 0 失败**（`BUILD SUCCESSFUL in 3m 58s`、`JS_EXIT=0`） |
| 全量单元测试 | `:app:testDebugUnitTest --no-daemon --console=plain` | `BUILD SUCCESSFUL in 4m 20s`；**71 suite、498 项、0 failures、0 errors、4 skipped**（上一轮 70/492 → +1 suite、+6 项） |
| Lint | `:app:lintDebug --no-daemon --console=plain` | `BUILD SUCCESSFUL in 5m 25s`、`LINT_EXIT=0`；21 warnings、0 errors（与基线同数），JS 相关文件 0 issue |

日志：`F:\AI-hub\agent\Hermes\cache\scratch\xy-reader-uitest\p4-final2.log`。

### 覆盖的行为

- **普通 JS**：`result` 入参 / 字符串与数组方法 / `JSON` / `RegExp`；
- **`java.*` 桥（Legado 兼容最小面）**：`getString(rule)`（在当前页面根上按 Legado 规则取值）、`getStringList`、
  `get/put` 变量、`ajax(url)`（复用来源请求与 header，同步入口）、`log`；全局 `baseUrl`/`sourceName`；
- **沙箱**：`typeof java.lang` / `java.io` / `Packages` / `javax` 全为 `undefined`（`ClassShutter` 拒绝一切类）；
  `new java.io.File('x')` 直接脚本失败；脚本异常转成可读失败；
- **超时**：慢脚本在 500ms 预算内被墙钟拦下，返回失败；随后引擎**毒化**，后续脚本立刻失败（不会每个源泄漏一个线程）；
- **接入点**：搜索（`ruleSearch.*`）、正文链路（`ruleBookInfo.tocUrl`／`ruleToc.*`／`ruleContent.*`）都已挂上 JS；
  搜索整体切到 `Dispatchers.IO`（`java.ajax` 需同步调用，不能阻塞主线程）。

## 一个被实测推翻的设计假设（记录在案）

原计划靠 Rhino 的 `observeInstructionCount` 中止死循环。**孤立探针实测：解释模式下不回调**——
`while(true){}`、`var i=0;while(i<1e9){i++;}`、`for(;;){}` 三种写法全部挂死（60s 超时），
加 `setGenerateObserverCount(true)` 也无效；`javap` 确认 `Context` 只有 `setInstructionObserverThreshold`/`setClassShutter`，
没有可中断执行的 API（Rhino 不响应 `Thread.interrupt`）。

因此改为**墙钟守卫**：脚本跑在独立单线程，`Future.get(timeout)`，超时返回失败并**毒化引擎**。
代价诚实写明：**被杀不掉的线程会一直空转**，所以"毒化"是最多泄漏一个线程——而不是吞掉整次搜索或每个源泄漏一个。
测试里刻意用「有限但很慢」的循环而非真死循环，避免同 JVM 的其他测试被空转线程拖慢。

另外 `ClassShutter` 的正确接法也是查 `javap` 得来的：`Context.setClassShutter(...)` 是**实例方法**，且 getter 非 public，
所以 Kotlin 的属性语法会编译失败——不能靠猜。

## 未实测 / 未实现

- **元素级 JS 返回**（`bookList` / `chapterList` 用 JS 返回节点集合）：未做（当前 JS 步骤只产出文本）；
- `java.*` 只实现了高频子集：`java.ajax` 无来源白名单/限速，`cookie`、`source.*`、`base64`、`md5` 等未做；
- **嵌套调用**：`java.getString` 内部的规则若再含 `@js:` 不会递归执行（桥用的是无 JS 的求值器）；
- 真机未验证；Rhino 1.7.15 在 Android 上的实际运行（D8/R8 处理、methods 数）**未在 release 构建里验证**——这是下一步必须先确认的；
- 未做脚本内存上限。

## 复现命令

```powershell
cd "F:\AI-hub\workroom\program\XY-reader"
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --tests "com.xyreader.booksource.LegadoJsRuntimeTest"
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
& ./gradlew.bat :app:lintDebug --no-daemon --console=plain
```
