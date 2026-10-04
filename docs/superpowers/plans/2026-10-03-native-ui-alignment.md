# Native UI Alignment Implementation Plan

**Goal:** 将已确认的移动端设计落到 Android App，保留真实业务功能，并落实本轮三处截图反馈。

**Spec:** `outputs/ui-preview/library-design.html` 及其模块作为视觉参考；原生仓库、书库、分组、配置和更新实现作为业务权威。执行者使用 gpt-6-luna / max，主代理规划、集成与验收。

## 用户反馈

- 书卡下方不再显示分组/已读文案和进度条；封面右下角只显示整数百分比 `xx%`。
- 删除首页重复的全部/未分组/分组/新建分类按钮条。分组区保留必要的新建入口，未分组可从书架入口访问。
- 首页分组三点菜单可打开，提供“重命名、删除分组”；删除需确认，书籍回到未分组。封面编辑继续从分组管理入口提供。
- 手机首页、书架和分组书籍三列；书架增加设计稿中的木柜/网格切换和抽屉展开。
- 全部真实页面按同一设计语言对齐，包括设置、配置、详情、仓库、阅读器及信息页。桌面侧栏用于 Android 宽屏布局，手机保持底部导航规则。

## 约束

- 不新增依赖，不改 Room schema、applicationId、签名、版本或 CI/构建配置，不发布、不提交、不推送。
- 不把 HTML 演示状态或假数据带入 App；复用现有 Repository、DataStore、导航和系统权限流程。
- 不删除既有交接文件、IDEA.md、旧 APK、用户书籍或配置。
- 子代理按文件独占执行，不运行 Gradle 或设备；主代理统一构建与设备验收。
- APK 使用独立后缀命名，保留原已发布包。MuMu 覆盖安装保留数据，验证只使用已有 QA 内容或本轮专用测试记录。

## 文件所有权

| 执行单元 | 文件 |
|---|---|
| 预览反馈 | `outputs/ui-preview/library-design.html`、`preview-mobile-pages.js/.css` |
| 原生首页与书卡 | `ui/HomeScreen.kt`、`ui/HomeGroupFolders.kt`、`ui/BookGrid.kt`、对应现有测试 |
| 原生书架 | `ui/ShelfScreen.kt`、新 `ui/BookshelfCabinet.kt`、`data/LibraryLayoutStore.kt`、对应测试 |
| 外观导航与其余页面 | 盘点后按独占文件分配，不与以上单元交叉 |

## 冻结接口

- `BookCard` 和 `BookGrid` 既有业务回调保持兼容。
- 书卡单元可新增 `internal fun BookReadingProgressBadge(book: BookEntity, modifier: Modifier = Modifier)`，书架封面复用同一百分比逻辑。
- 首页分组区新增 `onCreateGroup: () -> Unit`、`onDeleteGroup: (Long) -> Unit` 回调；使用现有 `removeGroup`，不删除书籍文件。
- 书架布局偏好只增 DataStore 字段（默认为书柜），不修改数据库；既有首页排序读写保留。

## 执行与验收

- [x] 修正预览百分比、重复分类条和菜单动作协议，浏览器点击验证。
- [x] 落地原生书卡三列、角标和首页紧凑木质分组区；菜单、拖组与排序保持有效。
- [x] 落地真实数据书柜、网格切换、展开、空态及分组/书籍操作。
- [x] 逐页盘点原生与设计稿差异，落实外观、宽屏导航和必要的其余页面对齐。
- [x] 复核改动边界与接口，执行单测、Release 构建、Lint（分别运行）。
- [x] 保存独立 APK，核版本/权限/签名及 dex 内容。
- [x] MuMu 验证首页/书架三列、百分比、菜单改名/删组、书柜/网格、阅读与设置；截图对照明暗主题。
- [x] 记录自动化与模拟器证据，明确未验证的真实硬件/外部账号边界。

## 集成复核补充

- 本地仓库、仓库配置、远程仓库与 Google Drive 的添加/表单区域逐页对齐；保留原有 SAF、扫描、保存与 OAuth 回调。
- 单测初轮已执行 145 项，2 项 Compose 空闲等待超时、2 项既有环境跳过。测试时钟处理修正后须全量复跑；不降低业务断言或删除生产自动滚动。

## 发布续项（2026-10-04）

用户在原生验收后明确要求提交、推送并发布安装包。发布阶段将版本递增为 0.5.3 / versionCode 27，沿用正式签名，复跑检查后提交本轮源码与文档、推送 main 并核对 CI。APK 和界面截图作为 GitHub Release 附件，outputs 继续保持忽略；原 APK、交接文档及 IDEA.md 保留。
