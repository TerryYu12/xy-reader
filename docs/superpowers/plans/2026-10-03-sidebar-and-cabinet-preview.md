# Sidebar and Cabinet Preview Implementation Plan

> **For agentic workers:** Use subagent-driven-development to execute each task. The controller plans and reviews; a gpt-6-luna worker with max reasoning produces the preview.

**Goal:** 交付侧栏与木质书柜的整体交互设计稿，供确认后再规划 Android 实现。

**Architecture:** 基于现有单文件预览创建独立副本，复用主题、假数据、导航、书籍详情和阅读器。侧栏增加折叠与分组导航；书架新增书柜视图；首页只增加紧凑分组抽屉。

**Tech Stack:** HTML、CSS、原生 JavaScript，现有图标与本地字体。

**Spec:** 本文的“设计规格”。需求来源：继续侧栏与书柜设计，两者一起，先出整体设计稿。

## Global Constraints

- 仅修改新的 HTML 设计稿；保留 `index.html`、`sidebar.html` 和旧截图。
- 主页保持现有 UI 基本不变，仅补分组效果。
- 使用虚构示例数据；不接真实书库、不新增依赖、不改 Kotlin、版本、数据库、CI 或构建配置。
- 不发布、不 commit、不 push、不删除文件。预览与截图放在已忽略的 `outputs/ui-preview/`。
- 保留主题切换、设置、阅读器和书籍详情交互；用户可随时切回网格。
- 不将本机路径、密钥或本地交接文档纳入公开内容。

## 设计规格

1. 侧栏展开宽度 244px，折叠宽度 72px。顶部品牌与折叠按钮；主导航首页、书架；收藏、历史、书签使用已有分区；自定义分组显示名称与数量；底部保留继续阅读与设置。
2. 小屏沿用 ≤800px 隐藏侧栏的规则与原底部导航。折叠后按钮保留可访问名称；离开小屏后恢复之前的折叠状态。
3. 书架页在原有摘要区下增加“网格 / 书柜”切换。书柜为默认设计展示，网格保留原书架内容。书柜展示全库书籍，书籍可打开已有详情。
4. 深胡桃木横板、薄高光切边、隔层阴影；分组用黄铜铭牌抽屉，显示实际示例数量及代表书。点击展开、再次点击收起，抽屉内书籍可点击；展开组不重复出现在散放书籍区。
5. 首页继续阅读、搜索、排序及书卡保持原有结构，在筛选区附近补紧凑抽屉入口。新增分组与改名沿用原有交互，数量和侧栏同步更新。
6. 木材只用于书柜与抽屉区域；应用外壳沿用现有明暗主题，浅色主题下文字、木板和黄铜铭牌可辨识。
7. 空分组显示空态；新增分组入口可操作。优先用 CSS 表现材质，展开动效不超过 250ms，支持 prefers-reduced-motion。
8. 工具框支持 390、780、1024、1280、1440 视口；按外层实际宽度缩放，不出现取景框自身横向溢出。

### Task 1: 接手基线核验

**Files:** 只读 `交接文档-2026-10-02.md`、方向与更新器源码、对应测试、已有预览与已发布 APK。

- [x] 核对 HEAD、已跟踪改动和故意未跟踪文件。
- [x] 比较实际源码、APK hash、签名、Release 与 CI；报告文档不一致及未验证项。
- [x] 区分已发布 Android 功能与 HTML 预览，提供证据给规划者。

核验结果：HEAD 为 `2649779`，与缓存的 `origin/main` 一致；Release `v0.5.2` 为 Latest，CI `37042831917` success。本地已发布 APK 的 MD5 为 `338f4b0ca2f5d9948edb678e8587edcf`，SHA-256 与 Release digest 相符。旧旋屏验证档的默认 true 与旧 MD5 未同步到最终状态，源码及默认值单测均为 false。HTML 尚无 App 已实现的木质抽屉。上述差异作为接手发现保留，本轮不修改生产行为。

### Task 2: 独立整体设计稿

**Files:** Create `outputs/ui-preview/library-design.html`、`outputs/ui-preview/library-design-frame.html`。

**Interfaces:** 复用已有 `state`、`navigate(next)`、`render()`、`homeView()`、`shelfView()`、`sectionView()`；保留既有 data 属性事件协议。新增状态仅用于侧栏折叠、书架布局与抽屉展开。

- [x] 复制原预览为 `library-design.html`，确保静态资产仍能从同目录解析。
- [x] 按设计规格完成侧栏、书柜、首页抽屉以及状态同步。
- [x] 创建自适应取景框，默认打开书架书柜视图，能切换 5 档视口。
- [x] 检查脚本语法与实际交互；只汇报修改的文件和明确验证结果。

### Task 3: 浏览器验收

**Files:** Read 新设计稿；Create `outputs/ui-preview/library-design-wide.png`、`outputs/ui-preview/library-design-mobile.png`、`outputs/ui-preview/library-design-light.png`。

- [x] 在真实浏览器运行设计稿，检查 console 与 page errors。
- [x] 验证 244/72px 侧栏切换、各导航入口、分组展开、书籍详情、网格切换、分组新增与改名同步。
- [x] 测量 390、780、1024、1280、1440 宽度的 document scrollWidth，不超过 viewport width；检查手机与宽屏导航规则。
- [x] 验证明暗主题、窄屏书柜与 reduced-motion，生成截图并目视检查文字重叠、裁切、木板与封面层次。
- [x] 控制者按规格与质量分别复核；有失败则退回同一执行者修复，成功后展示可交互设计稿。

## 后续边界

本轮完成标准是可交互设计稿与浏览器证据。Android 侧栏适配、书柜数据接线与发布需在设计确认后另立任务。

## 移动端比例修正

用户指出手机预览偏矮。390 档此前根据外层面板高度计算 iframe 高度，窄面板实际出现约 390×523 的视口。手机档改为固定 390×844，并按取景区域的可用宽、高整体缩放；外层尺寸只改变缩放率，不改变手机视口比例。默认打开手机档，其余四档仍可切换。

- [x] 执行者仅修改取景框，完成固定尺寸、居中适配与手机尺寸整数读数。
- [x] 控制者在当前浏览器确认 iframe 390×844、画框完整可见、比例正确，并保存截图。
- [x] 验证其余档位仍可切换且不产生页面横向溢出。
