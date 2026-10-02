# XY-READER 0.5.1 更新器与分组文件夹验证

日期：2026-10-02

XY-READER 0.5.1 修复了内置更新器，新增首页分组抽屉，并将 Room 数据库从 v5 迁到 v6 以保存分组封面。本地交付版本为 0.5.1/versionCode 25；没有新增依赖或额外调整 CI/构建设置。

## 变更

更新器将发布 JSON 解析失败视为检查失败，非法版本不再误判为新版。下载完成时对照 Release 资产大小、HTTP 声明和实收字节，拒绝零字节 APK，并保留协程取消。更新弹窗宿主放在 NavHost 外，设置页可显示检查结果；未知来源安装权限页返回后保留已下载 APK；缺少 APK 或下载失败时可打开发布页。

主页新增横向木质抽屉卡，显示分组名和书数，支持打开分组、改名、选封面和清除封面。书卡长按可以拖入抽屉；文件夹和分类 chip 使用不同投放 key，组件卸载时会注销坐标。封面导入先检查图像尺寸并采样缩小，压缩成功后才写 Room；分组封面列为可空字段，数据库从 v5 迁到 v6。清除或替换封面只更新数据库引用，保留旧封面副本。

## 自动化与构建

| 检查 | 结果 |
|---|---|
| `:app:testDebugUnitTest --no-daemon --console=plain` | 135 tests，0 failures，0 errors，2 skipped；统计来自测试 XML |
| `:app:assembleRelease --no-daemon --console=plain` | BUILD SUCCESSFUL |
| `python scripts/copy-delivery.py` | 复制 Release 0.5.1；脚本拒绝复制残留的 Debug 0.4.8 |
| `:app:lintDebug --no-daemon --console=plain` | BUILD SUCCESSFUL；Lint XML 为 21 warnings、0 errors、0 information，改动文件无 Lint issue |

前两次 Release 构建都因本仓库的闲置 Gradle 8.10.2 daemon 占用 R8 输出的 `classes.dex` 失败。Restart Manager 确认了占用进程，`gradle --status` 显示 IDLE；wrapper `--stop` 停止该 daemon 后，独占只读探针确认锁已释放，下一次构建成功。没有清理构建目录或改构建设置。

单测通过后做了两处等价 Lint 清理：minSdk 26 下直接查询安装权限，并将进度状态改成整数状态。之后 Release 与 Lint 都重新编译通过；没有为这两处清理重跑全量单测。

## APK

交付包：`outputs/XY-READER-0.5.1.apk`，22,629,229 bytes。aapt 确认包名 `com.xyreader`、versionName `0.5.1`、versionCode `25`，并含 INTERNET 与 REQUEST_INSTALL_PACKAGES 权限。签名证书为 `CN=XY Reader`，SHA-256 为 `d1cc619dfcb1c5e85f18a1a6d6ed1b614dd44f2a2e2d333f2ebe80ac48b08c35`。

交付包 MD5：`506A4390B5417017FEC77A8DD541F8EA`；SHA-256：`17B1B65073174AD6117205C0F99CF3A6F2EBED0B721B9E79CA11C16681591789`。Release APK 有一个 5,138,320-byte `classes.dex`，更新器、安装入口和分组封面相关字符串均在包内。原有 `outputs/XY-READER-0.5.0.apk` 的 MD5 仍为 `B76D13E5DDE4E39DDBC77FF50ED86951`，未被覆盖。

## MuMu Android 15 验收

升级前安装的是 0.5.0/versionCode 24，数据库 v5 有三本书且没有分组。旧版 UI 新建了 `MigrationQA` 分组（id 1），并将 `test4p`（书 id 2）放入该组；另两本书没有改动。使用 `adb install -r` 覆盖安装 0.5.1 后，应用成功打开，Room 将数据库迁到 v6。只读查询确认分组和书籍关系保留，`coverPath` 初始为 null。

首页打开该分组后确认 `test4p` 出现在组内；抽屉菜单将组名改为 `MigrationQARenamed`，SQLite 查询和首页文字随即更新。系统照片选择器导入验收截图作封面后，提示“分组封面已更新”，数据库封面路径变为非空，首页卡片显示封面。最后用 `input touchscreen draganddrop` 把 `broken`（书 id 3）拖进抽屉；SQLite 中 `groupId` 变为 1，卡片从 1 本更新为 2 本。首页截图保存在 `outputs/XY-READER-MuMu-home-top.png`；封面样本截图保存在 `outputs/XY-READER-MuMu-before-cover.png`，MuMu 的 Pictures 中也留有同一张测试图片。

应用启动时没有出现更新弹窗；设置里的“版本”手动检查显示“已是最新版本 v0.5.1”。当前 GitHub latest 是 0.5.0，低于本地 0.5.1，因此没有新版可下载。没有反转生产版本比较、创建测试 Release 或声称下载到系统安装器的链路通过。

MuMu 的 Wi-Fi 和移动数据原先开启。曾尝试离线验收时，`svc data disable` 调用卡住，ADB 进入 offline；通过 MuMuManager 内部 shell 恢复 Wi-Fi，复核状态为 airplane mode=0、Wi-Fi=1、mobile data=1，并重启 adbd 后恢复 ADB。该故障发生在安装 0.5.1 之前；应用后来在线升级成功。离线启动静默分支没有实测。自动化 SQL 测试覆盖非空分组迁移；MuMu 则覆盖了应用实际 Room 迁移和界面交互。强调色保持升级前截图所示的珊瑚色，没有修改；模拟器已关闭。QA 分组、封面测试图和 UI 采样文件保留在模拟器中。
