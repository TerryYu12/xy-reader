# 侧栏与书柜整体设计稿验证

日期：2026-10-03

本轮交付独立 HTML 设计稿，Android 应用仍为已发布的 0.5.2。执行者为 gpt-6-luna，reasoning effort 为 max；主代理负责规划、浏览器验收与结果复核。

## 产物

- `outputs/ui-preview/library-design.html`：整体交互设计稿。
- `outputs/ui-preview/library-design-frame.html`：五档视口取景框。
- `outputs/ui-preview/library-design-wide.png`、`library-design-mobile.png`、`library-design-light.png`：真实浏览器截图。
- `outputs/ui-preview/library-design-verification.json`：浏览器读回记录。
- `outputs/ui-preview/verify-library-design.cjs`：本轮浏览器检查脚本。

设计稿、截图和检查辅助文件均位于已忽略的目录。原 `index.html`、`sidebar.html` 与既有截图保留。本轮未改 Kotlin、版本、数据库、构建配置或 CI；未运行 Gradle、安装 APK、发布或提交。

## 浏览器结果

使用隔离的 agent-browser session 与本机 Chrome，直接点击页面控件，再读取 DOM、应用状态及实际尺寸。

| 检查 | 结果 |
|---|---|
| 主稿视口 390 / 780 / 1024 / 1280 / 1440 | document scrollWidth 均等于视口宽度 |
| 展开与折叠侧栏 | 244px / 72px；等动效结束后测量 |
| 折叠后切小屏，再返回宽屏 | 小屏隐藏且无溢出，返回后仍为折叠状态 |
| 收藏 / 历史 / 书签导航 | 三个入口均进入对应 section |
| 小说抽屉 | 展开显示 4 本书，aria-expanded=true |
| 点击抽屉内书籍 | 进入已有书籍详情，activeBook 对应所点书籍 |
| 网格切换 | cabinet-view 为 none，shelf-grid-view 为 block，原 4 个分类入口保留 |
| 首页 | 原 7 张书卡保留，新增紧凑分组条 |
| 新建空分组 | 侧栏、首页、书柜名称同步，空态可读 |
| 已展开分组改名 | 名称同步，openDrawer 跟随新名称并保持展开 |
| 名称冲突回归 | 创建与改名各 6 例，均拒绝已有组名及全部、未分组、收藏、历史、书签；数据保持不变 |
| 侧栏无障碍状态 | 折叠 aria-expanded=false，展开为 true |
| 图标引用 | missing symbols=[] |
| 明暗主题 | 浅色切换成功；截图检查文字、封面与铭牌无重叠 |
| prefers-reduced-motion | 模拟媒体偏好生效，侧栏按钮 transitionDuration=0.00001s |
| 取景框五档 | iframe 内层宽度对应所选档；外层 700px 的 scrollWidth 始终 700px |
| 页面错误 | errors=[] |

验收期间修复了缺失图标、改名状态未同步、名称冲突导致重复抽屉，以及侧栏未暴露展开状态。修复后重新执行相关交互与尺寸检查。独立子代理先检查规格，再检查质量，最终结论为规格 PASS、质量 APPROVED。

## 验证边界

设计稿仅使用虚构数据。木柜的视觉选择需要用户审阅；Android 适配、真实书库接线、传感器体验与系统安装器均不属于本轮验证。

## 手机取景比例修正

此前 390 档的高度跟随外层面板，用户实际看到约 390×523，导致手机画面偏矮。取景框现将手机档固定为 390×844，按可用宽高整体缩放并居中，默认打开手机档。

在用户当前的应用内浏览器读回：iframe clientWidth=390、clientHeight=844；显示画框约 256×551，完整位于取景区域内；页面 clientWidth 与 scrollWidth 均为 375。其余 780、1024、1280、1440 档均可切换且页面无横向溢出，最后恢复手机档。两份 HTML 的内联脚本语法检查通过。当前截图为 `outputs/ui-preview/library-design-mobile-fixed.png`；此前截图保留。
