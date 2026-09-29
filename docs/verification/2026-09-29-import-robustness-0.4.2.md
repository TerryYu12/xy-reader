# 0.4.2 验证档 —— 导入加固 + 交付命名

日期：2026-09-29
版本：**0.4.2 / versionCode 14**

## 本版解决什么

1. **用户反馈：「文件管理器里选 pdf/txt → XY-reader 打开 → 显示无法打开 xxxxx」**
   - 前置好消息：0.4.1 的可见性修复**生效**——应用已出现在「打开方式」列表且能点开启动。
   - 新的失败点在**应用内的导入环节**（toast = `无法打开这个文件：<原因>`）。
2. **用户要求：交付包文件名带应用名 + 版本号。**

## 改动清单

| 文件 | 改动 |
|---|---|
| `data/LibraryRepositoryImpl.kt` | ①类型判定链升级：名称扩展名 → MIME 兜底 → **文件头魔数嗅探**；②错误消息细化（带名称/类型/权限原因）；③MIME 映射补 `application/x-zip-compressed` |
| `app/build.gradle.kts` | `appVersionName` 单点变量（0.4.2）；versionCode 14 |
| `scripts/copy-delivery.py` | 新交付脚本：读 `appVersionName` → 复制产物为 `XY-READER-<版本>.apk` |
| `app/src/test/.../SharedImportTest.kt` | +3 嗅探用例；原「不支持类型」用例改为二进制内容（文本现在会被嗅探成 txt） |

### 魔数嗅探覆盖
`%PDF`→pdf；`PK`→epub（mimetype 条目）/zip；`Rar!`→rar；7z 魔数→7z；`BOOKMOBI`→mobi；前 1KB 无 NUL→txt；其余拒绝（提示带名称与 MIME，便于真机定位）。

## 验证证据（全部本机实测）

- 测试：**71/71 通过**（`app/build/test-results/testDebugUnitTest/*.xml` 聚合，0 failures / 0 errors）
- lint：**0 errors**（19 既有 warnings）
- 构建：`:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug` → BUILD SUCCESSFUL in 11m6s（108 tasks）
- 版本核验（aapt dump badging，两个包一致）：
  - `versionCode='14' versionName='0.4.2'`
- APK 抽检：
  - release：单 `classes.dex` 4,892,116 B（R8 合并，非丢 dex）
  - debug：9 dex（classes~classes9），无缺失
  - release 清单字符串（utf-16-le）：`octet-stream` ✓ `x-zip-compressed` ✓ `BROWSABLE` ✓ `OPENABLE` ✓ `application/pdf` ✓ `text/plain` ✓

## 交付物

| 文件 | 字节 | md5 |
|---|---|---|
| `outputs/XY-READER-0.4.2.apk` | 22,352,501 | `c934363992c76b917cc225ad5fb8cabd` |
| `outputs/XY-READER-0.4.2-debug.apk` | 40,110,816 | `80cbe0b1d3df02670d65aba404f575db` |

## 交付流程（新约定）

```
BUILD SUCCESSFUL 出现在日志  →  python scripts/copy-delivery.py  →  aapt dump badging 核 versionName/versionCode
```

> 教训（本版亲历）：构建未完成就跑拷贝脚本，会把上一版产物复制成新版本名。**必须先确认日志出现 BUILD SUCCESSFUL，再交付**；aapt 核验是最后一道闸。

## 已知偏差 / 未闭环

- release 的 `optimizeReleaseResources` 仍会把 pathPattern 反斜杠吃掉一层（`.*\.txt` → `.*.txt`），SIMPLE_GLOB 下行为等价，属既有无害偏差。
- **用户真机待验证**：0.4.2 下「文件管理器 → 打开方式 → XY-reader」是否成功阅读。
  - 若失败，需要用户提供：弹窗提示**全文**（截图）、手机品牌型号与 Android 版本、所用文件管理器名称。
  - 若提示为「权限被拒绝」类：属文件管理器未向接收方授权读取（应用侧无法代授），需进一步针对性方案。
- 本机无设备/模拟器，真机体验环节必须由用户执行。

## 回滚

- 上一版 APK 仍在：`outputs/app-release-0.4.1.apk`（0.4.1 功能基线）。
- 本版四个改动文件快照：`backups/0.4.2-post-state/`；回到 0.4.1 代码 = 按上表反向还原（导入块、MIME 映射、嗅探函数、测试、版本号与脚本）。
