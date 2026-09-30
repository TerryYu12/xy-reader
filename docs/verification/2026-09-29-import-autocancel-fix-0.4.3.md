# 0.4.3 验证档 —— 「无法打开」自取消 bug 修复

日期：2026-09-29
版本：**0.4.3 / versionCode 15**

## 用户报告

真机：文件管理器选 PDF/TXT → 「用 XY-READER 打开」→ 应用能启动，但弹
`无法打开这个文件：The coroutine scope left the composition`。
（前置：0.4.1 已解决「打开方式列表不出现」；本条是新暴露的第二步问题。）

## 根因（实锤）

`ui/Navigation.kt` 的导入 effect 自取消：

```kotlin
LaunchedEffect(shared) {      // ← key = shared
    SharedIntake.consume()    // ← 消费即把 shared 置 null
    repository.importSharedFile(...)  // ← key 变了 → Compose 取消正在跑的导入协程
}
```

- 取消异常类型 = `LeftCompositionCancellationException`（Compose runtime 该类字符串常量即为
  "The coroutine scope left the composition"；在本地依赖
  `androidx.compose.runtime/runtime-android/1.7.6` 中逐字节命中核实）
- `runCatching` 又把取消异常当失败吞掉 → 弹出误导 toast

## 修复（三件）

1. **`LaunchedEffect(Unit)` + `SharedIntake.pending.collect`**：消费只更新流值、不再改 key；
   协程寿命 = 组合存续期
2. **取消不再被吞**：`try { Result.success(...) } catch (CancellationException) { throw }
   catch (Exception) { Result.failure }`——被真取消时不弹误导提示，且 pending 保留重试
3. **导航前 `withFrameNanos` 等一帧**：消除冷启动「导入早于 NavHost setGraph 完成」竞态
   （`Cannot navigate to reader/N?page=0. Navigation graph has not been set`——测试先行抓到）

另：`SharedImportFlowTest`（全链路回归：Robolectric + 真 `ArkNavHost` + `SharedIntake` + 真仓库 +
`ShadowToast` 断言无失败提示）——该类 bug 从此过不了门禁。

## 验证证据

- 单元测试：**72/72 通过**（`build-043c-test2.log`：BUILD SUCCESSFUL in 1m56s；
  结果 XML 聚合 0 failures / 0 errors）
- Release + lint：**BUILD SUCCESSFUL in 12m40s**（`build-043-pkg.log`，80 tasks；
  lint 0 errors / 19 既有 warnings）
- aapt 核验（两包一致）：`versionCode='15' versionName='0.4.3'`
- APK 抽检：release 单 `classes.dex` 4,892,960 B（R8，非丢 dex）；
  清单字符串：`octet-stream` ✓ `BROWSABLE` ✓ `OPENABLE` ✓ `application/pdf` ✓

## 交付物

| 文件 | 字节 | md5 |
|---|---|---|
| `outputs/XY-READER-0.4.3.apk` | 22,352,501 | `d2f86faf201229e19705080684197c44` |
| `outputs/XY-READER-0.4.3-debug.apk` | 40,270,447 | `316828c00aef29ceece7945c5e508c2f` |

## 环境观察（如实记录）

- 本机存在**另一会话（另一个 Hermes bot）在同一项目目录并行构建**：一次全链构建因
  `Unable to delete ...testDebugUnitTest\binary\output.bin`（文件锁）中止；更早一次因内存
  挤压守护进程被打死。本版两次成功构建均是趁其安静的窗口完成。**建议两会话约定：同一时间
  只允许一个会话在本目录跑 Gradle。**
- 项目已建 git 仓库 `github.com/TerryYu12/xy-reader`（私有）+ GitHub Actions CI
  （`.github/workflows/android-ci.yml`，push main 触发）；CI 首跑失败于 KSP 插件依赖解析
  （阿里云镜像链），修复待办。

## 回滚

- 上一版 APK：`outputs/XY-READER-0.4.2.apk`；代码回退点见 git 提交 `7fcc699` 之前的本地
  `backups/0.4.2-post-state/`。

## 真机待验证（用户执行）

1. 卸载旧版 → 安装 `XY-READER-0.4.3.apk` → 设置页底部应显示 **0.4.3**
2. 文件管理器 / MT 管理器：选 PDF 或 TXT →「打开方式」→ XY-READER →
   预期：直接导入并进入阅读界面（无错误弹窗）
3. 若仍异常：提供弹窗**全文**截图 + 机型/Android 版本 + 所用文件管理器名称
