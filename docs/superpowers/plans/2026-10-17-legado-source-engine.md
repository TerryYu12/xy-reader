# Legado 兼容书源引擎（主仓零源）

日期：2026-10-17。基线：`564137b`。前置：`docs/research/2026-10-17-zlibrary-and-other-catalogs-probe.md`。
Master 决策：**按建议来——主仓只做通用引擎（不含任何源数据），Z-Library 适配器单独拆仓。**

## 中立边界（硬规则，先立后做）

1. 主仓**不含任何书源数据**：不放源 JSON、不放示例源、不放指向具体站点的规则；测试只用合成的 `example.com` 假源。
2. 主仓**不出现具体站点名/商标**：代码、注释、文档、UI 文案一律不提来源站名。
3. 凭据、Cookie、登录态只存在用户设备；不做服务端中转，不代存。
4. 免责声明与「自带内容（bring-your-own-content）」定位写进 README（与 Legado/Mihon 现行形态一致）。
5. Z-Library 适配器只存在于**独立仓库/可选插件**，主仓只留 `BookSource` 接口。

## 实测：真实书源的规模与语法分布（重要，决定分期）

样本：`legado.aoaostar.com` 的聚合书源文件，**2664 条可解析**（该分类共 3907 条），14.8 MB。分析脚本与原始数据在本机临时目录（不入库）。

**字段模型（来自实测，非凭记忆）**

- 顶层：`bookSourceName`、`bookSourceType`、`bookSourceUrl`、`bookSourceGroup`、`bookSourceComment`、`enabled`、`enabledExplore`、`enabledCookieJar`、`weight`、`customOrder`、`lastUpdateTime`、`respondTime`、`searchUrl`、`exploreUrl`、`loginUrl`、`loginUi`、`loginCheckJs`、`bookUrlPattern`、`header`、`concurrentRate`、`enabledReview`、`variableComment`、`coverDecodeJs`、`exploreScreen`、`jsLib`
- `ruleSearch`：`bookList`、`name`、`author`、`kind`、`coverUrl`、`intro`、`wordCount`、`lastChapter`、`bookUrl`、`checkKeyWord`
- `ruleBookInfo`：`init`、`name`、`author`、`kind`、`intro`、`coverUrl`、`tocUrl`、`wordCount`、`lastChapter`、`canReName`、`downloadUrls`
- `ruleToc`：`chapterList`、`chapterName`、`chapterUrl`、`updateTime`、`isVip`、`isVolume`、`isPay`、`nextTocUrl`、`preUpdateJs`、`formatJs`
- `ruleContent`：`content`、`nextContentUrl`、`replaceRegex`、`imageStyle`、`sourceRegex`、`webJs`、`title`
- 另有 `ruleExplore`（2383）、`ruleReview`（1315）

**规则语法分布（46898 条规则串）**

| 写法 | 占比 |
| --- | --- |
| JS（`@js:` / `<js>` / `java.`） | 6% 的规则；**58% 的源完全不含 JS** |
| JSONPath（`$.` / `@json:`） | 10% |
| 正则（`##` / `@regex:`） | 16% |
| 模板 `{{ }}` | 4% |
| 回退 `\|\|` / 串联 `&&` / 交错 `%%` | 3% / 3% / 0.02% |
| 显式 `@css:` / XPath | 0.3% / 0.8%（**默认选择器语法才是主体**） |

**类型分布**：`bookSourceType` 0=小说 **2562**、2=漫画 **55**、1=32、3=15；**208 个源需要登录**。

结论（直接决定分期与预期）：不实现 JS 就能覆盖约 **58%** 的源；漫画源只有 55 条（**别承诺漫画覆盖**）；登录源要单独处理或明确不支持。

## 架构

```
用户粘贴/导入源 JSON（主仓不携带）
        ↓  LegadoBookSource（模型，字段以上实测为准）
源管理（启用/禁用/分组/删除；凭据只存本机）
        ↓
LegadoRuleEngine（选择器求值：默认 CSS 语法 → JSONPath → 正则 → 模板 → XPath → JS）
        ↓  搜索 → 详情 → 目录 → 正文（多页 nextContentUrl 拼接）
复用既有下载底座（BookDownloadManager：命名/校验/先落盘后入库）→ 书架 → 阅读器
```

## 分期

| 期 | 内容 | 依赖 | 状态 |
| --- | --- | --- | --- |
| P1 | 书源模型 + 规则串解析（前缀/`\|\|`/`&&`/`##`/`{{ }}`，JS 不切分） | 无（仅 org.json） | ✅ 已完成（10 项测试） |
| P2 | 选择器求值：默认 CSS 语法 + `@css:` + JSONPath 子集 + 正则 + 模板 + 回退/串联 | jsoup 1.23.2（**已批**） | ✅ 已完成子集（15 项测试）；XPath/JS 未实现 |
| P3 | XPath 求值 | **未新增依赖**（`javax.xml.xpath` 自带） | ✅ 已完成 |
| P4 | JS 规则沙箱（Rhino + `java.*` 桥） | rhino 1.7.15（**已批**） | ✅ 已完成子集（6 项测试）：文本级 JS + 桥；**元素级 JS 返回未做** |
| P5 | 源管理 UI + 搜索/目录/正文 UI + 下载队列 | 无 | ✅ **P5a/P5b/P5c 全部完成**（源仓库、多源搜索、目录/正文/整本入库、源管理页、搜索页、设置入口；55 项相关测试） |

## 验证计划

- P1：规则解析纯函数测试（前缀识别、回退/串联切分、`##` 替换、模板标记、JS 含 `||` 不被切分、空规则）；模型解析测试（数组/单对象、缺字段、未知字段、`enabled=false`）。全部用合成源。
- P2 起：对**可控的本地 HTTP 夹具**（自建页面）验证求值，再对用户自行导入的源做端到端；不把真实源纳入 CI。
- 真机验收：用户导入自己的源 → 搜索 → 下载 → 断网阅读。

## 待 Master 批准

1. **P2 新依赖**：HTML/CSS 解析库（Jsoup，Apache-2.0，约 400 KB，无网络权限）。不引就只剩手写选择器，覆盖率和可维护性都会差一截。
2. **P4 新依赖**：JS 引擎（Rhino 或 QuickJS）+ 自建 `java.*` 桥；这是最大的一块工程，覆盖约 42% 的源。
3. **Z-Library 适配器独立仓**：需要你点头建仓（对外发布动作），我才会创建。
