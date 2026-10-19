# 在线书库与漫画下载接入调研

核对日期：2026-10-17（America/New_York）。源码基线：`564137b`。本轮只调研和写文档，没有接入书源、登录账号或下载书籍正文。

建议先做 **OPDS 1.2 目录浏览、搜索、下载入库**，用 Calibre 验证电子书，用 Kavita 或 Komga 验证漫画。它们提供明确的协议或项目官方文档，可以共用下载管线。Z-Library 可找到第三方逆向接口资料，但本轮未找到可验证的官方开发者 API 文档，不能把它列为稳定的内置下载源。

这条方案解决“在软件里找到书、下载到本地、离线阅读”的链路。Calibre、Kavita、Komga 需要用户自己的服务器和已有文件；它们不会自动提供中文新书或连载漫画资源。公共书库的语言、年代和可下载范围也不同，不能承诺覆盖想看的所有书。

## 当前项目可以复用什么

| 已有能力 | 当前源码证据 | 接入时的含义 |
| --- | --- | --- |
| 本地文件导入 | `core/Repository.kt:44` 的 `importSharedFile()`；`data/LibraryRepositoryImpl.kt:245` | 接收 `content://` / `file://`，复制到 `filesDir/imported` 后入库；不能直接传入 HTTP 下载地址。 |
| 格式识别 | `core/BookFormat.kt` | 支持 EPUB、PDF、TXT、Markdown、MOBI/AZW3，以及 CBZ/ZIP、CBR/RAR、CB7/7Z、CBT/TAR。优先下载 EPUB、PDF、CBZ，不应把支持扩展名等同于支持全部 DRM 或压缩变体。 |
| 阅读器路由 | `core/ArchiveFactory.kt:72` | 识别本地文件和 `webdav://`、`gdrive://`；不存在 OPDS URL 或通用 HTTP 图书的路由。完成整本下载后复用本地阅读器，改动较小。 |
| WebDAV 仓库 | `ui/RemoteReposScreen.kt:76`；`core/Repository.kt:91-104` | 已有配置、测试连通性和扫描入库入口，适合放置“在线书库”入口的参考；OPDS 用 GET 目录，WebDAV 用 PROPFIND，不能把 OPDS 地址直接当 WebDAV。 |
| WebDAV 文件传输 | `data/WebDavClient.kt:145` 的 `downloadToFile()` | 能下载文件，但没有通用书籍下载任务、恢复、来源去重等语义。远程阅读缓存也不等于用户可管理的离线下载。 |
| 网络权限 | `app/src/main/AndroidManifest.xml` | 已声明 INTERNET，且允许局域网 HTTP。公网认证目录建议使用 HTTPS。 |

必须处理一个已有导入边界：`importSharedFile()` 按清洗后的文件名生成目标路径，并在找到同 URI 时覆盖文件、复用记录（`LibraryRepositoryImpl.kt:270-307`）。两个书库中同名文件不能直接共用该命名方式，否则可能覆盖另一版本。下载落盘名应带来源 ID、条目 ID 和版本/格式信息，再交给导入流程。

## 候选服务对比

### OPDS 与 Calibre

OPDS 1.2 基于 Atom XML；目录导航、搜索、书目详情和获取链接已有规范。`acquisition`、`acquisition/open-access`、`borrow`、`buy` 等链接含义不同，目录能列出书不代表可直接下载。首期只处理已授权的直接文件获取，借阅、付费、间接获取及 DRM 应显示来源说明或打开原站。OPDS 2.0 使用 JSON，建议后续增加，避免首期同时维护两套解析器。[OPDS 规范](https://specs.opds.io/opds-1.2)、[规范版本说明](https://specs.opds.io/)

Calibre Content Server 可提供用户书库的浏览和下载，OPDS 常用入口为服务地址下的 `/opds`。用户启动自己的服务，手机访问其局域网地址；需要外网时自行配置域名/反代。认证可能是 Basic 或 Digest，不能认为已有 WebDAV Basic 代码能覆盖所有部署。首期可明确支持 HTTPS + Basic，Digest 目录需给出配置说明或另补支持。[Calibre Content Server](https://manual.calibre-ebook.com/server.html)、[calibre-server 选项](https://manual.calibre-ebook.com/generated/en/calibre-server.html)

适用：已有电子书收藏、NAS 或 PC 书库。限额由部署者、反向代理和网络决定，没有可套用到所有部署的统一日下载数。文件能否阅读取决于服务实际返回格式。

### Kavita：电子书与漫画共用一个目录

项目官方文档提供 OPDS、OPDS-PS 和 API。每位用户有独立 OPDS URL / Auth Key；搜索和目录分页均有说明。API 文档给出下载端点，并要求用户具备下载权限。首期从用户复制的 OPDS URL 开始，按目录链接浏览和下载整本 EPUB/PDF/漫画文件，避免硬编码每种系列/卷/章 URL。[OPDS 功能](https://wiki.kavitareader.com/guides/features/opds/)、[API 文档](https://www.kavitareader.com/docs/api/)

Auth Key 相当于账号访问凭据。密钥可能位于 URL 路径中，日志、错误报告、分享和截图必须脱敏；保存时使用系统凭据保护能力，不能因为称为“目录地址”就按普通公开 URL 处理。[第三方客户端设置](https://wiki.kavitareader.com/guides/user-settings/3rdpartycilents/)

适用：用户自建电子书/漫画库。下载权限、账户可见库、代理超时都会影响结果。OPDS-PS 按页流式阅读及进度同步留到后期；现有 `HttpRangeChannel` 针对压缩文件字节范围，不能直接替代图片页协议。

### Komga：漫画优先

官方提供 OPDS 1.2 和 OPDS 2.0，入口分别为 `/opds/v1.2/catalog` 与 `/opds/v2/catalog`（部署有 base URL 时保留前缀）。OPDS v1 文档还列出 OpenSearch 和 Page Streaming Extension 1.2。[OPDS 客户端说明](https://komga.org/docs/guides/opds/)

REST API 文档支持 Basic 和 `X-API-Key`。API key 不创建 session。首期通过 OPDS 获取有权限的单本 CBZ/CBR/PDF/EPUB；若通用客户端未支持 key header，可用独立的来源认证适配，不能把凭据随意附到任意跳转域名。[Komga API 与认证](https://komga.org/docs/openapi/komga-api/)

适用：漫画系列、卷册和阅读清单。整套打包与单册下载应分开处理，避免把多层 ZIP 当作可直接阅读的 CBZ。实际可下载格式及权限需用部署者的实例验证。

### Project Gutenberg 与 Gutendex

Gutendex 是第三方开源的 Gutenberg 元数据 JSON 服务，不是 Gutenberg 官方 API。`/books` 支持书名/作者搜索、语言和 MIME 筛选，返回分页链接及 `formats` 下载地址映射。维护者要求长期使用者自行部署服务；不能承诺公共实例有 SLA。[Gutendex 文档](https://gutendex.com/)、[维护者仓库](https://github.com/garethbjohnson/gutendex)

可用 EPUB/TXT 做下载入库样例，但需要单独确认下载路由：Gutenberg 官方限制一般网站自动访问，给出 harvest、镜像及机器可读目录入口；其链接说明还要求优先链接书籍落地页，文件深链可能变化或被拦。Gutendex 返回 URL 不等于对所有自动下载方式的授权。不要爬搜索 HTML，也不要预取全库正文。[自动访问政策](https://www.gutenberg.org/policy/robot_access.html)、[链接和使用说明](https://www.gutenberg.org/policy/permission.html)

推荐定位：公开元数据和格式适配验证，待确认允许的文件获取方式后再作为默认下载源。`copyright: false` 是来源元数据，不能替代逐项许可证及适用地区判断。

### Standard Ebooks

官方有 OPDS 目录 `https://standardebooks.org/feeds/opds`，并可通过 `Accept: application/opds+json` 请求 OPDS 2.0。**完整 OPDS 目录不是无需账号的公共源**：官方 feeds 页面说明 New Releases RSS/Atom 向所有人开放，其他 feeds 属于 Patrons Circle 等访问资格；客户端使用该资格对应的 email 作为用户名，密码可留空或按客户端要求填任意值。[官方 feeds](https://standardebooks.org/feeds)、[官方阅读器配置说明](https://standardebooks.org/help/how-to-add-an-opds-feed-to-your-ereader)

适用：有访问资格的用户获取精校电子书，优先 EPUB。未经 Master 授权不注册付费资格，也不把目录访问受限写成书籍文件全部收费。RSS/Atom 新书通知不能直接当作完整 OPDS 下载目录。

### Open Library 与 Internet Archive

Open Library 的 Search API 适合书名、作者和版本发现。它主要解决书目检索；检索命中或存在 `ia` 标识不代表能下载整本。官方要求缓存、标识应用，当前公开限额为匿名 1 request/s，标识应用 3 requests/s；不应多 IP 绕限制。[API 使用指南](https://openlibrary.org/developers/api)、[Search API](https://openlibrary.org/dev/docs/api/search)

Internet Archive 的元数据接口可读取 item 文件列表；官方 metadata schema 有 `access-restricted-item`、`geo_restricted` 等字段。工程上需要按条目和文件识别公开下载资源，HTTP 401/403/404 仍要独立处理。受限借阅与 DRM 不属于通用 EPUB/PDF 下载；首期只对用户有权获取的公开文件显示下载按钮，其余保留原站入口。[官方开发资源](https://archivesupport.zendesk.com/hc/en-us/articles/360001495812-Developer-Resources)、[官方 metadata schema](https://archive.org/services/docs/api/metadata-schema/index.html)

推荐定位：后续补充书目和公开扫描版来源。不要承诺“Open Library 所有书都可下载”，也不要把封面下载当作正文下载成功。

### Z-Library

本轮能核对的是两个第三方项目：`bipinkrish/Zlibrary-API` 明示 Unofficial wrapper；`baroxyton/zlibrary-eapi-documentation` 明示 Inofficial，并记录账号 token/header、登录、搜索等逆向约定。后者还记载下载曾依赖 personal domain。这些只能证明社区存在接口尝试，不能证明当前域名、认证、额度和下载接口仍可用，更不能证明其由平台官方公开支持。[第三方 wrapper](https://github.com/bipinkrish/Zlibrary-API)、[第三方接口记录](https://github.com/baroxyton/zlibrary-eapi-documentation)

未找到可验证的官方开发者 API 文档，因此当前状态是“官方 API 未确认”，不是“证明不存在 API”。本轮没有尝试登录、获取 token、探测镜像或下载正文，也不列出未经确认的域名。

可能出现的失败包括接口和域名变化、会话过期、账号配额、验证码、设备校验和下载 URL 有效期；哪些机制当前实际启用，仍需针对官方渠道和有权访问的账号确认。不得用历史第三方 README 推导固定每日下载额度。若后续仍考虑接入，应先确定可信渠道、账号授权和条目获取权限，再评估独立可关闭适配器；不建议首期内置或作为下载承诺。

## 推荐分期

### 第一期：让通用下载链路可用

新增在线书库列表、用户填写 OPDS 地址、匿名/Basic 认证、目录分页、OpenSearch、书目详情和格式选择。以用户自己的 Calibre EPUB 和 Komga/Kavita CBZ 验证搜索到离线阅读的完整路径。新增来源配置或持久化任务若需要 Room schema 迁移，应按项目审批规则单独确认后执行。

建议下载链路：

`目录条目 → 检查可获取方式/格式 → 创建下载任务 → 临时文件 → 校验响应与实际类型 → 唯一命名落盘 → 复用导入和封面生成 → 打开书籍`

下载按钮区分“下载”“加入队列”“已下载”“重试”。切到其他页面或后台时任务应有明确状态；应用被系统结束后的恢复需要持久化任务及平台调度机制，不能只在页面 coroutine 中执行。APK 更新和书籍下载可以参考共同的生命周期处理，但安装校验、书籍入库与存储策略应保持各自语义。

最少需要处理：

- 401/403 认证或权限问题、429 限速、网络超时和临时 URL 过期；有限重试并尊重 `Retry-After`。
- HTML 登录页伪装成 EPUB、缺少/错误的 MIME、无 `Content-Length`、重定向和不支持 Range；不要只看扩展名就报下载成功。
- 下载途中空间不足、取消、半成品、同名不同书、重复点击、来源版本更新。先完成文件再入库，不把半本书显示为已下载。
- 目录和封面有大小限制，XML 禁用外部实体；相对 URL 按 feed URL 解析。凭据仅发送到明确授权的来源，跨主机跳转不转发认证信息。
- source ID 与远端 item ID 决定下载身份，书名用于展示；下载链接或带密钥的目录路径不能进普通日志。

### 第二期：公共内容与漫画体验

在确认服务访问条件和下载规则后补 Gutendex 等来源适配；Standard Ebooks 支持用户已有资格。针对漫画补系列/卷/章层级、单册队列和批量大小提示。再决定是否需要 OPDS 2.0、OPDS-PS 与 Komga/Kavita 进度同步，避免首期把“下载”扩展成远程阅读器重写。

### 暂缓项目

Z-Library 非官方适配、需要购买/借阅及 DRM 的完整链路、任意网页抓取和第三方漫画扩展市场。每项都有独立认证或内容获取约束，当前证据不足以作可用承诺。

## 已核对与证据边界

| 检查项 | 本轮结果 |
| --- | --- |
| 当前仓库协议、导入、格式与 WebDAV 入口 | 已读上述 Kotlin 源码；没有修改应用代码。 |
| OPDS、Calibre、Kavita、Komga、Open Library、Gutenberg 文档 | 已通过公开文档核对，链接附在对应结论后。文档可访问不代表 Master 的服务器可连接。 |
| Standard Ebooks feeds 页面 | PowerShell `Invoke-WebRequest` 得到 HTTP 200，Content-Type 为 `application/xhtml+xml`；正文明确区分公共新书 RSS/Atom 与受资格限制的其他 feeds。 |
| Gutendex 单书元数据 | `https://gutendex.com/books/1342/` 一次请求返回 HTTP 200 / JSON，包含 `formats` 中 EPUB 地址。没有请求该正文文件。 |
| IA metadata schema | PowerShell 获取 HTTP 200，已核对访问限制字段。 |
| 后续搜索/元数据探测 | Gutendex 搜索、Open Library 搜索、IA `metadata/xfetch` 及 Standard Ebooks OPDS 的 PowerShell 请求遇到 TLS 建连错误；这是本次网络链路失败，不能据此判定源站不可用或应返回何种 HTTP 状态。 |
| 应用内搜索、下载、入库和断网阅读 | 未实测：本轮没有实现在线书库。 |
| Master 自建服务、账号权限和真机网络 | 未实测：未提供目标实例或设备，不读取私人账号、不发起登录。 |
| Z-Library 当前登录和下载能力 | 未验证，只能确认第三方资料自称 unofficial；未确认官方 API、可用域名或当前额度。 |

后续验收需在 Master 实际使用的 Android 安装包和网络下操作：添加自建目录，搜索一本授权 EPUB 和一本授权 CBZ，进入后台继续下载，完成后断网阅读；再覆盖错误密码、无下载权限、同名异书、取消和系统回收后的恢复。书封展示、系列布局等画面待 Master 目检。
