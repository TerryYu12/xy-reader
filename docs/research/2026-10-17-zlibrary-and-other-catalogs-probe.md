# 复核：Z-Library / 其他书库现在到底能不能接

日期：2026-10-17（America/New_York）。基线：`564137b`。
背景：上一份调研（`2026-10-17-library-catalogs.md`）只查到「第三方 wrapper 存在」，未做接口实测，结论偏保守。
本轮**直接打接口**复核，结论有修正。

## 结论（修正）

**Z-Library 能接，而且比我上次说的可行得多。** 它的安卓 App 私有接口（EAPI，第三方文档记录，非官方发布）**此刻从本机可达，且匿名搜索不需要账号**。
「不能接」的说法不成立；真正的问题不是能不能，而是**必须接成什么形态**（见「形态约束」）。

## 实测证据（2026-10-17，本机 curl 直连，无代理）

| 探测 | 结果 | 说明 |
| --- | --- | --- |
| `POST https://z-lib.gd/eapi/user/login`（空 body） | `400 {"success":0,"error":"Incorrect email or password"}` | 接口活着、返回真 JSON |
| `POST https://z-lib.gd/eapi/book/search`（`message=dune&limit=5`，**无任何凭据**） | **`200`**，返回 `books[]`：`id`/`title`/`author`/`language`/`extension`/`filesize`/`md5`/`cover`/`identifier`(ISBN) | **匿名搜索可用**，元数据齐全 |
| `GET https://z-lib.gd/eapi/info` | `200 {"success":1,"books":33636484,"articles":82096323,"plans":{...}}` | 3364 万本书 / 8209 万篇文献；plans 里含下载额度与限速 |
| `GET https://z-lib.gd/eapi/book/{id}/{hash}` | `200`，返回单书详情 | 匿名可取详情 |
| `GET https://z-lib.gd/eapi/info/domains` | `200`，列出活域：`z-library.sk`、`1lib.sk`、`article.sk`、`articles.sk`、`proxy.zlibraryproxies.workers.dev` | **官方域列表接口**，客户端可自愈 |
| 域轮换实测（同一时刻逐个探 `eapi/user/login`） | 活：`z-lib.gd`、`z-lib.gl`、`z-library.ec`、`1lib.sk`；死：`zlib.bz`、`article.sk`、`articles.sk`（000）；`z-lib.fm` → 307 | 轮换是真的，但同刻仍有多个活域 |
| `GET https://z-lib.gd/`（HTML 站） | **503** | HTML 站被 DiamWall 挡；EAPI 反而畅通 |

要点：**搜索/详情/域列表全部匿名可用；只有「下载」需要账号 + 个人域**（第三方 EAPI 文档明确：下载必须用个人域；
官方 blog 另称游客每 IP 可下 5 本，本轮**未验证**，也未下载任何正文）。

## 形态约束（这三条才是真正的决定项，与技术无关）

1. **凭据属于用户**：下载要 `remix_userid`/`remix_userkey`（email+password 换）**加个人域**。应用只能设计成「用户自填、可一键清除、默认关闭」，不能内置任何人的账号。
2. **域会轮换、反爬会变**：本轮实测同刻就有 4 活 3 死。好消息是有 `/eapi/info/domains`，适配器能自愈；坏消息是接口本身是逆向出来的，随时可能被改。
3. **公开仓库不得内置盗版站客户端**（有先例，不是道德说教）：Tachiyomi 于 2024-01 遭 Kakao Entertainment 法律威胁，官方扩展仓被清空、
   项目停更、被迫转成 "bring-your-own-content"（官方只留 Komga/Kavita 等自建服务扩展），社区另起 Mihon。
   `TerryYu12/xy-reader` 是**公开 MIT 仓库**，内置 Z-Library 下载器会把风险直接写在 master 名下，且可能招来 DMCA 下架。

## 其他候选（同轮实测）

| 来源 | 实测 | 判断 |
| --- | --- | --- |
| Anna's Archive | 4 个域（`.org/.se/.li/.gl`）`/faq` 全部 `000`，报 `schannel: failed to receive handshake`（直连与代理都失败）；官方 FAQ 自述**没有搜索 API**，只有会员的 `/dyn/api/fast_download.json`（拿直链）与 torrent 批量数据 | **本机完全不可达 + 无搜索接口**，性价比最低，暂不考虑 |
| Standard Ebooks | `feeds/opds` 匿名 **401**（上一轮实测） | 非公共源 |
| Gutendex / Gutenberg | 200 JSON，`formats` 给 EPUB（指向 ebook 页），已核 | 公版英文，可作样例源 |
| Legado（开源阅读）书源生态 | `aoaostar/legado` **6905★**，最近推送 **2026-10-17**（当天）；站点列出书源分类计数 3907 / 128 / 939 条；官方姿态「软件不提供内容，需用户自己导入书源」 | 中文小说 + 漫画**唯一能规模化拿到内容**、且法律姿态与公开仓库相容的路线 |
| Mihon/Tachiyomi 扩展生态 | 官方仓在 2024-01 事件后只剩自建服务扩展（Komga/Kavita 等） | 漫画源只能走第三方私有仓或 Legado 形态，不能照搬 |

## 建议形态（待 master 拍板）

- **主路线：Legado 兼容书源**（用户导入 JSON 规则）。应用不含内容，来源可增删、可关闭；覆盖中文小说与漫画。
  代价：规则引擎（CSS/XPath/JSONPath/正则 + JS 需沙箱），工程量最大。
- **Z-Library 适配器（可选，用户自填凭据）**：匿名搜索可以内置（只查不下载，风险低），下载必须用户填自己的账号与个人域；
  默认关闭、凭据可清除、失败可自愈（读 `/eapi/info/domains`）。这是本轮唯一能立刻端到端跑通的真源。
- **Anna's Archive**：不做（不可达且无搜索 API）。
- 下载/校验/入库/命名底座已于 2026-10-17 完成（`docs/verification/2026-10-17-book-download-infrastructure.md`），两个适配器都接在它上面。

**未做**：未登录任何账号、未获取任何 token、未下载任何书籍正文；上述「能不能下载」只依据接口与第三方文档，未用真实账号验证。
