# Legado 书源引擎 P4-release 验证记录（R8/Rhino 出包修复）

日期：2026-10-18（承接 2026-10-17 的 P4 记录）。分支：`codex/startup-update-background`。
上游记录：`docs/verification/2026-10-17-legado-source-engine-p4.md`（其中「Rhino 未在 release 构建里验证」一条在本档关闭）。
范围：**只修 release 出包链路**（R8 因 Rhino 引用 `java.beans.*` / `java.awt.*` / `javax.swing.*` 失败），不升版本号、不改业务代码、不 commit。

## 结论

**release 出包已通。** `:app:assembleRelease` 由 FAILED 转为 `BUILD SUCCESSFUL`（8m 33s），
四处 `-dontwarn` + 一条 `-keep` 全部写在 `app/proguard-rules.pro`，业务代码零改动。
versionCode=33 / versionName=0.5.9 未变，Rhino 类经 R8 后**完整保留且未被重命名**，`com.xyreader.booksource.*` 单元测试 0 失败。

一个与预设不同的点：任务给的修复方向只预测了 `java.beans.**`，**实际还有一整批 `java.awt.**` / `javax.swing.**`**——
它们来自 Rhino jar 里自带的桌面 GUI 调试器（`org.mozilla.javascript.tools.debugger.*` / `tools.shell.*`），
Android 上根本不加载。第一轮只加 `java.beans` 后 R8 仍失败，第二轮补齐才通过（两轮日志见下）。

## 已实测

| 检查 | 命令 / 方法 | 真实输出 |
| --- | --- | --- |
| release 构建 | `./gradlew.bat :app:assembleRelease --no-daemon --console=plain` | **`BUILD SUCCESSFUL in 8m 33s`**、`REL_EXIT=0`、52 tasks（11 executed / 41 up-to-date） |
| 产物 | `ls -l app/build/outputs/apk/release/app-release.apk` | `23,426,687` 字节（2026-10-18 06:18），单一 `classes.dex` = 6,883,620 字节、`resources.arsc` = 175,068 字节 |
| 产物哈希 | `sha256sum` | `86d6787f9a84fd7f76613d9a589f6c6b2a6a478144ace3fc5eea87e8a05b2614` |
| 版本号核对 | `aapt.exe dump badging`（build-tools **35.0.0**） | `package: name='com.xyreader' versionCode='33' versionName='0.5.9'`、`sdkVersion:'26'`、`targetSdkVersion:'34'`——**与改动前一致** |
| 权限核对 | 同上 | 含 `uses-permission: name='android.permission.INTERNET'` |
| 签名 | `apksigner verify --print-certs` | `CN=XY Reader, O=TerryYu12, C=CN`（正式 release 签名，非 debug） |
| Rhino 保留 | `grep org.mozilla.javascript app/build/outputs/mapping/release/mapping.txt` | **19,072** 行命中；**556** 个 `org.mozilla.javascript.*` 类定义 |
| Rhino 未被重命名 | 同上，看 `A -> B` | `Context -> Context`、`Interpreter -> Interpreter`、`ScriptableObject -> ScriptableObject`、`BaseFunction -> BaseFunction`、`NativeArray -> NativeArray`、`RhinoException -> RhinoException`、`JavaToJSONConverters -> JavaToJSONConverters`、`ClassShutter -> ClassShutter`（左名=右名，未混淆） |
| 未误留桌面类 | `grep -cE "^(java\.beans\|java\.awt\|javax\.swing)\..* -> "` mapping.txt | **0**——只静默了告警，没把 Android 不存在的类打进包 |
| missing_rules 清零 | `cat .../mapping/release/missing_rules.txt` | **文件已不存在**（上一轮失败时该文件列出 123 条 `-dontwarn`） |
| booksource 单测 | `./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --tests "com.xyreader.booksource.*"` | **`BUILD SUCCESSFUL in 1m 23s`**、`TEST_EXIT=0`；解析 XML 统计：**7 个 suite、58 项、0 failures、0 errors、0 skipped**（LegadoBookDownloader 4 / ContentService 7 / **JsRuntime 6** / RuleEvaluator 18 / SearchService 8 / SourceParsing 10 / SourceStore 5） |

日志（均在同机 `F:\AI-hub\agent\Hermes\cache\scratch\xy-reader-uitest\`）：

- `p4-release-r8fix.log` —— 第一轮（只加 `java.beans`）：`BUILD FAILED in 5m 45s`、`REL_EXIT=1`，失败于 `:app:minifyReleaseWithR8`；
- `p4-release-r8fix2.log` —— 第二轮（补齐 `java.awt.**`/`javax.swing.**`）：**`BUILD SUCCESSFUL in 8m 33s`**；
- `p4-release-r8fix-tests.log` —— booksource 单测。

### 两轮失败的原始报错（存档）

- 原始失败（`p4-release.log`）：`Missing class java.beans.BeanDescriptor (referenced from: org.mozilla.javascript.JavaToJSONConverters.lambda$static$4)`，另有 `BeanInfo` / `IntrospectionException` / `Introspector` / `PropertyDescriptor`。
- 第一轮修完后新暴露：`Missing class javax.swing.JTree (referenced from: org.mozilla.javascript.tools.debugger.MyTreeTable... )` 等，共 **123 条**，**全部**落在 `java.awt.*`、`java.awt.event.*`、`javax.swing.*`（含 `.border/.event/.filechooser/.table/.text/.tree`）。
  用 `grep -oE "Missing class [A-Za-z0-9_.]+" ... | sed 's/\.[A-Z][A-Za-z0-9_$]*$//' | sort -u` 得到的包列表只有这 9 个前缀，**没有任何第三方/应用类缺失**——所以按包静默是安全的，没有用到 `-dontwarn **` 这种一揽子写法。

## 修复内容（`app/proguard-rules.pro`，每条规则的理由）

追加 4 条规则、共 3 组，全部有实测依据：

1. **`-dontwarn java.beans.**`**
   依据：R8 报 `java.beans.{BeanDescriptor,BeanInfo,IntrospectionException,Introspector,PropertyDescriptor}` 缺失，
   唯一引用点是 `org.mozilla.javascript.JavaToJSONConverters.lambda$static$4`——Rhino 把 Java 对象转 JSON 时的 Bean 内省分支。
   书源 `@js:` 规则只跑字符串/数组/JSON 处理，走不到这条分支；Android 运行时 `java.beans` 不存在，保留它没有意义 → 静默告警，不 keep。

2. **`-dontwarn java.awt.**` + `-dontwarn javax.swing.**`**
   依据：第一轮修复后 R8 暴露的第二批缺失（123 条）**全部**来自 Rhino jar 自带的桌面 GUI 工具
   （`org.mozilla.javascript.tools.debugger.*`、`tools.shell.*`），它们拿 AWT/Swing 画调试器界面。
   Android 上没有 AWT/Swing，这些类永远不加载；`mapping.txt` 复查确认没有任何 `java.awt`/`javax.swing` 定义被打进包（0 条）——即只是消音，不是增重。
   （对比：任务书只预测了 `java.beans`，此处是实测补出来的第二组，理由如上，非"无脑 dontwarn"。）

3. **`-keep class org.mozilla.javascript.** { *; }`**
   依据：Rhino 是脚本引擎，运行时按字符串拼类名加载（如 `new Context`、`Context.exit`、优化器/解释器切换），
   R8 无法从静态调用图证明这些类可达，默认会裁掉或重命名 → 必须整体 keep。
   实测证据：`mapping.txt` 中 `Context -> Context`、`Interpreter -> Interpreter` 等**左名等于右名**（未混淆），556 个 Rhino 类定义全部在册。

未改动 `app/build.gradle.kts`、未新增依赖、未动任何业务源码。

## 未实测 / 风险

- **APK 体积变化无法精确归因**。改动前磁盘上的 `app/build/outputs/apk/release/app-release.apk`（22,678,381 字节）经 `sha256sum` 比对，
  与 `outputs/XY-READER-0.5.3.apk` **逐字节相同**（`f6d752d6b7c283a99e02b4368870563840f16f45403ef0aa8e10125c46dd91d7`，`aapt` 核为 `versionCode=27 / versionName=0.5.3`）——
  也就是说它是**残留的旧 0.5.3 产物，不是可用的 0.5.9 release 包**。因此 `+748,306` 字节（23,426,687 − 22,678,381）**不能当作 Rhino 的净增**，
  它混了 0.5.3 之后全部改动（下载底座、书源引擎、界面）。**本机没有可比的上一版 0.5.9 release APK，Rhino 的独立体积成本未测**。
- **Rhino 未做瘦身**：`-keep ...** { *; }` 让 556 个类全量保留（含 `tools.debugger`、`tools.shell`、`optimizer` 等非必需部分）。
  后续若要压体积，可改为只 keep 沙箱实际用到的包，但**必须先有 release 真机回归**兜底，本次不动。
- **真机未验**：本次只证明「能出包、版本对、类在、单测绿」。release APK 里 JS 书源规则的**实际运行**（D8/R8 产物 + 真机 ART）
  仍未跑过——这正是 P4 记录里挂起的最后一项，需在真机/模拟器上用一个合成 JS 源回归（P4 的 `LegadoJsRuntimeTest` 只覆盖 JVM 侧）。
- **未跑 `:app:lintDebug`**：本次范围只到 release 出包，lint 结论沿用 P4 记录（21 warnings / 0 errors）。
- **未跑全量单测**：只跑了 `com.xyreader.booksource.*`（58 项）。proguard 规则不参与 `testDebugUnitTest` 的编译/运行路径，
  理论上无影响，但"全量 71 suite / 498 项"仍以 P4 那轮为准，本轮未复跑。
- **未 commit / 未 push**，也未打包发版。`app/proguard-rules.pro` 处于 `M`（已修改未暂存）状态。工作区内其它未提交改动一律未碰。

## 复现命令

```powershell
cd "F:\AI-hub\workroom\program\XY-reader"
& ./gradlew.bat :app:assembleRelease --no-daemon --console=plain
& "C:\Users\11\AppData\Local\Android\Sdk\build-tools\35.0.0\aapt.exe" dump badging app/build/outputs/apk/release/app-release.apk | Select-String "package|INTERNET"
& Select-String "org.mozilla.javascript" app/build/outputs/mapping/release/mapping.txt | Measure-Object
& ./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --tests "com.xyreader.booksource.*"
```
