# 原生界面替换验证

日期：2026-10-04。设计依据为 `outputs/ui-preview/library-design.html` 及移动端页面模块；业务实现以 Android 仓库为准。

## 改动

- 首页、书架列表与分组书籍采用三列；封面右下角显示整数百分比，移除书卡底部元信息及进度条。
- 首页删除重复分类按钮条，改为紧凑木质分组区。三点菜单提供重命名、删除；删组前确认，书籍转至未分组。
- 书架接入真实书籍及分组数据，支持书柜/网格切换、抽屉展开和持久化布局偏好。
- 宽屏增加可折叠侧栏；计数、分组、继续阅读及设置均使用实际数据与导航。阅读器隐藏侧栏。
- 设置增加系统/浅色/深色选择，沿用八种强调色。分组管理接入系统图片选择器及实际封面导入。
- 详情页采用紧凑封面与信息布局；保留文件大小、统计、目录、阅读及管理操作。
- 本地、远程仓库与 Google Drive 的添加入口移入页面。仓库配置采用路径说明卡、单一表单卡及保存/取消行；保留真实 SAF、扫描、配置与 OAuth 调用。
- 默认封面为外部角标预留空间，并适配窄封面。系统栏图标跟随应用实际主题；木底新建按钮使用高对比前景色。

未修改版本、签名配置、依赖、Room schema 或 CI。未提交、推送或发布。原 `outputs/XY-READER-0.5.2.apk` 保留，MD5 为 `338f4b0ca2f5d9948edb678e8587edcf`。

## 自动检查

- `:app:testDebugUnitTest`：BUILD SUCCESSFUL，XML 合计 145 项，143 通过、0 失败、0 错误、2 跳过。跳过项为 `WebDavLocalServerTest.listChineseDirParsesFiles` 与 `listRootDirParsesEntries`，未用它们证明外部云端可用。
- `:app:assembleRelease`：BUILD SUCCESSFUL，6 分 38 秒。
- `:app:lintDebug`：BUILD SUCCESSFUL；报告为 0 错误、21 警告。其中 `ShelfSectionHead` 的 Modifier 参数顺序警告为本轮非阻塞样式问题，其余包括 SDK、依赖版本、既有阅读器状态读取及资源告警。
- 日志：`outputs/native-ui-unit-20261004-complete.log`、`native-ui-release-20261004-final.log`、`native-ui-lint-20261004-final.log`。
- `git diff --check`：无空白错误；Git 对部分文件提示 LF/CRLF 规范化。

最终包：`outputs/XY-READER-0.5.2-native-ui-20261004-final.apk`，22,678,381 bytes。

SHA-256：`3ebd8ea4731c6cd74fd7aa185cbc6785d6230979ef0507ff0269d08d44092ed8`。

`aapt` 校验：`com.xyreader`、versionName `0.5.2`、versionCode `26`；含 INTERNET、REQUEST_INSTALL_PACKAGES 与应用内部 receiver 权限。`apksigner verify --print-certs` 通过，CN 为 XY Reader，证书 SHA-256 为 `d1cc619dfcb1c5e85f18a1a6d6ed1b614dd44f2a2e2d333f2ebe80ac48b08c35`。

APK 内含单一 `classes.dex`（5,242,944 bytes）；已检出 `shelf_cabinet_view`、木质书柜及新的 Google Drive 说明。APK 总大小与中间包相同，但 SHA-256 与 DEX 内容不同，未以总大小判断是否为新构建。

初轮单测运行了 145 项，新增的分组重命名与未分组拖放测试失败。拖放测试改为测量生产组件注册的真实目标，然后执行长按拖放并断言回调；不注入目标坐标，不修改生产自动滚动。

重命名测试的渲染模式、宿主尺寸与字段调整均未单独解决等待超时，孤立方法仍复现。窗口诊断随后显示：新 Dialog 初始为 `0×0`、未附着；只推进 Compose 时钟不足以完成 Android 窗口遍历。测试改为有限同步推进 Robolectric 主 Looper、Compose 时钟及窗口绘制，直到新 Dialog 就绪且本次 PopupLayout 已退出。原始真实输入与 trim 断言保留，未提高 Espresso 超时。单方法与最终全量均已通过。

本轮已实际验证 Gradle 类/方法过滤可用，类过滤执行 7 项，方法过滤执行 1 项。诊断日志保存在 `outputs/native-ui-rename-isolated-20261004-*`。

## MuMu 流程

环境为 MuMu Android 15、实例 0。覆盖安装保留原有三个 QA 书籍与一个分组。手机配置为 `1080×2400 / 420 dpi`；侧栏使用临时 `180 dpi` 验证 960 dp 宽屏，已恢复手机配置。

| 流程 | 观测 |
|---|---|
| 首页 | 三个封面同排，右下角 `50% / 0% / 0%`；无重复分类条、书卡底部进度条与元信息 |
| 分组菜单 | 原生三点菜单实际打开重命名与删除项 |
| 创建/改名/删组 | 创建专用 `UIQA1004`，改为 `UIQARenamed`；将原未分组 QA 书暂移入，删除该测试组后书籍返回未分组，原组与三本书保留 |
| 书柜 | 抽屉可展开/收起，分组封面、书籍和散放区使用真实数据 |
| 网格 | 能切换，强制停止并重启后仍保持网格；书架全部列表为三列；结束恢复书柜 |
| 详情与阅读 | 实际 PDF 显示文件大小、页数及目录状态，可继续阅读并滚动；阅读进度恢复原 QA 位置 |
| 外观 | 浅色、深色、跟随系统及强调色选择均有实际响应；蓝紫选择显示在设置行，结束恢复深色/珊瑚 |
| 本地仓库 | 使用新建的空 QA 目录完成 SAF 添加及零新增扫描；取消不保存改名，保存后列表显示新名；清理空测试仓库记录，原文件未删除 |
| 云端表单 | WebDAV 与 Google Drive 的页面内添加入口能打开真实表单，可返回关闭；未提交账号信息 |
| 分组封面 | 能打开系统图片选择器；取消后原封面仍显示 |
| 侧栏 | 展开、折叠、分组列表跳转及继续阅读均有效；阅读器内容从屏幕左边缘开始，侧栏隐藏 |

截图与 XML 记录在 `outputs/XY-READER-native-ui-20261004-*`。最终包已再次覆盖安装，复核浅色状态栏、木底按钮、改名表单与 Google Drive 新说明。最终浅色截图为 `home-light-blue-final.png`。空测试目录 `/sdcard/Download/XYReaderUIQA1004` 留在模拟器，内无文件；未删除用户文件。

## 验证边界

MuMu 验证不等同真实 Android 硬件验证。此次没有外部 WebDAV 或 Google Drive 账号，未验证真实联网扫描、OAuth 回调与云端下载。真实阅读、分组、设置和本地目录流程已在模拟器执行。
