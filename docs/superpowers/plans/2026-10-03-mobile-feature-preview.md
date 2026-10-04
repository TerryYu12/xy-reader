# Mobile Feature Preview Implementation Plan

> **For agentic workers:** Execute with gpt-6-luna / max workers and review against the source UI. File ownership is exclusive; the controller plans and verifies.

**Goal:** Android 当前可见功能均有移动端预览页面、弹窗或状态流程，补齐强调色等缺失功能。

**Architecture:** 沿用整体设计稿和 390×844 取景框。主文件负责外观及集成；新增两个原生 JavaScript 模块分别负责管理页面和阅读配置。共用现有虚构书籍与导航状态，通过冻结的接口连接，避免多人同时修改主文件。

**Tech Stack:** 现有 HTML、CSS、原生 JavaScript 与本地静态资产；不新增依赖。

**Spec:** 下列覆盖范围，依据 `ui/Navigation.kt`、各 Screen、`ThemeAccent.kt`、`core/ReaderPrefs.kt` 与阅读器快捷面板。

## Global Constraints

- 只修改 `outputs/ui-preview/` 内指定预览文件；Android 源码、构建、CI、版本和数据库不变。
- 保留书柜、折叠侧栏、首页、书籍详情、原有导航及 390×844 手机比例。
- 按用户追加要求，移动端首页、书架、分组书籍网格统一三本一排，木柜展开与散放区也按三格排列；桌面原有响应式布局保留。
- 网络、授权、扫描、安装流程只操作虚构数据；界面明确标示演示结果。不请求真实账户、不下载或安装 APK、不发起付款。
- 账号字段不写入 localStorage、不发送给服务端；取消表单后丢弃演示凭据。
- App 的“标签管理开发中”保留该状态，不伪造已经存在的业务。
- 原预览、历史 APK、截图及本地交接文档保留；不删除、提交、推送或发布。

## 覆盖范围

1. 外观：八档强调色、实时全局着色、所选标记、明暗主题与跟随系统；使用 App 中的明暗色值。品牌使用已在 App 打包的白色前景与强调色背景。
2. 显示：自动旋屏设置、阅读配置与阅读器半屏面板共用开关；方向策略提供可见的说明，浏览器不冒充传感器实测。
3. 本地仓库：列表、添加文件夹选择预览、扫描进度与结果、配置仓库名称/封面名/默认分组、移除确认及空态。
4. WebDAV：列表、新增/编辑、名称/服务器/用户名/密码、演示测试连接、扫描反馈与移除确认。
5. Google Drive：列表、Client ID/Secret/文件夹 ID 表单、演示授权等待与结果、扫描及移除确认。
6. 分组：增删改、删除后书归未分组、重名校验；首页抽屉菜单的改名、自定义封面及清除封面。书籍菜单的刷新/自定义封面提供选择界面和可见结果。
7. 版本：检查状态、已是最新、新版本说明、下载进度、未知来源授权提示、安装器预览、失败与重试；页面明确这些为演示状态。
8. 隐私与支持：渲染现有 App 文案和已打包的公开收款码图片，不执行任何付款。
9. 阅读配置：翻页/漫画方向、手势、图片缩放/质量、方向/常亮/自动旋屏、亮度 1%–100% 及跟随系统、六种字体及导入字体界面、字号/字重/行距/四边距/字距/首行缩进/章首新页。
10. 阅读器：上述配置与半屏面板同步；亮度、目录、书签、进度、复制文字弹窗、收藏、手势锁与解锁均有可操作预览。字体/间距实际影响 DOM 正文。

## 冻结接口

管理模块定义 `window.createMobilePages(api)`；阅读模块定义 `window.createReaderPreview(api)`。脚本加载在主 inline script 之前，工厂在首次 `render()` 前初始化。

`api` 提供：`state`、`render()`、`renderOverlays()`、`navigate(next)`、`goBack(fallback)`、`showToast(message)`、`escapeHtml(value)`、`icon(name, extra)`、`subpageHead(title, subtitle, fallback)`、`bookById(id)`、`cover(book)`、`groupNameIssue(value, currentName)`、`choiceCard(...)`、`pillCard(...)`、`switchCard(...)`、`rangeCard(...)`、`pageModeGroup()`、`pageDisplayGroup()`、`fontGroup()`、`applyReaderPrefsToDOM()`。

管理模块返回：`renderInfo(key)`、`renderGroups()`、`renderExtraOverlays()`、`onClick(target, event)`、`onInput(event)`、`onSubmit(event)`。渲染无匹配返回 null；事件已处理返回 true，未匹配 false。管理页面沿用 `state.view="info"` 与 `state.infoKey`（repos/remote/drive/privacy/support/version/repo-config）；数据保存在 `state.previewPages`。

阅读模块返回：`renderConfigGroup(tab, context)`、`renderBrightnessPopover()`、`renderExtraOverlays()`、`onClick(target, event)`、`onInput(event)`、`onSubmit(event)`、`afterRender()`。context 为 config 或 sheet；配置页显示完整选项，快捷模式页仅翻页、快捷页面页仅背景/缩放/自动旋屏/常亮，快捷字体页显示字体及排版。亮度保留独立弹层。未匹配返回 null/false。数据主要复用 `state.prefs`，导入字体等扩展存于 `state.previewReader`。

事件属性使用 `data-mobile-*` 和 `data-reader-extra-*` 前缀，工厂内部变量使用各自命名空间。模块 CSS 限于自己的类，不覆盖整页主题。

## 执行任务

### Task 1: 源码覆盖盘点

- [x] 只读列出全部当前页面及弹窗，与预览对照，记录缺项与真实参数。

已确认缺失外观色板、系统主题、本地/WebDAV/Drive 管理页、版本流程、隐私支持、封面编辑与导入字体；默认亮度应为 null（跟随系统），亮度范围为 1%–100%，边距步长 2px。图片质量运行时默认“标准”，以字段与 Store 回退为准。快捷面板控件集合与管理页不同，按上面的 context 契约分别呈现。

### Task 2: 外观与模块集成

**Files:** Modify `library-design.html`、`serve-library-design.cjs`；Copy 已跟踪的白色图标前景到同目录。

- [x] 强调色八档与三态主题可选择，全局主色与选中态实时更新。
- [x] 接入上述两工厂与事件钩子，替换占位页面、禁用的版本/封面入口，原路由保持可用。
- [x] 为书籍和分组的示例封面渲染提供可选图片属性；保留正常默认封面。
- [x] 本地预览服务支持模块 JS/CSS，仅服务已有预览目录与允许的静态文件。

### Task 3: 管理页面

**Files:** Create `preview-mobile-pages.js`、`preview-mobile-pages.css`；Copy 已跟踪的支持页图片。

- [x] 完成本地/WebDAV/Drive 页面、表单、扫描结果、空态与确认弹窗。
- [x] 完成分组管理、首页抽屉与书籍封面入口、删除确认及状态同步。
- [x] 完成版本状态流程、隐私和支持页；不再用“未纳入预览”替代现有功能。

### Task 4: 阅读配置与快捷界面

**Files:** Create `preview-reader-parity.js`、`preview-reader-parity.css`。

- [x] 对齐源码参数、单位和默认值，完整呈现配置页与阅读器设置面板。
- [x] 亮度跟随系统、导入字体、复制文字都有界面；配置修改影响实际正文。
- [x] 原目录、书签、进度、收藏与锁手势保留可用。

### Task 5: 集成验收

- [x] 校验所有脚本语法、模块资源加载与页面错误。
- [x] 在浏览器逐一打开所有入口，操作各主流程，检查返回、取消及状态同步。
- [x] 验证八种强调色在明暗主题下更新，配置和半屏面板读回一致。
- [x] 检查 390×844、窄桌面和宽桌面，无新增溢出或文字裁切。
- [x] 核验手机首页、书架最近阅读、分组列表和木柜书籍均为三列，四本书按三本加一排列。
- [x] 生成覆盖矩阵与截图，独立审查规格及质量，修复缺陷后展示移动端设置与强调色。
