# Handoff：在线书库与漫画下载接入调研

- **Date**：2026-10-17（America/New_York）
- **Status**：ready for review（调研）；接入尚未实现。
- **Workspace**：`F:\AI Flies\hermes work\XY reader`

## Goal

调研 Z-Library 或其他书库的接入途径，希望未来在软件内搜索并下载书籍和漫画。本轮授权是调研，不是直接加入任何书源。

## Done

- 子代理完成调研，主代理已读回。完整文档：`docs/research/2026-10-17-library-catalogs.md`，核对日期 2026-10-17，源码基线 `564137b`。
- 核对当前格式识别、本地导入、阅读器路由、WebDAV 入口；调研 OPDS、Calibre、Kavita、Komga、Gutenberg/Gutendex、Standard Ebooks、Open Library/Internet Archive 与 Z-Library 第三方资料。
- 首期建议为 OPDS 1.2 浏览/搜索/格式选择/下载入库；用 Calibre 的 EPUB、Komga 或 Kavita 的 CBZ 验证完整路径。
- Z-Library 只确认到自称 unofficial 的第三方资料，未确认官方开发者 API、可用域名、当前认证或额度；不把它列为首期稳定内置源。
- 来源文档与 URL、HTTP 探测结果和 TLS 失败均在调研中分开记录。没有登录私人账号，没有下载书籍正文。

## In flight

没有书库实现代码或运行任务。推荐方案已明确，但 Master 尚未选择实际书源/实例；应用里没有新增在线搜索、书籍或漫画下载功能。

| 已实测 | 未实测 | 已知缺口 |
| --- | --- | --- |
| 当前 Kotlin 源码和公开资料核对；Standard Ebooks feeds 页面、Gutendex 单书元数据、IA schema 的 HTTP 200 证据由调研记录保存。 | 应用内搜索下载入库、Master 自建实例和账户权限、Z-Library 当前登录下载。 | 后续部分公开接口探测遇 TLS 错误，不能由此断言源站不可用；自托管服务本身不提供用户尚未拥有的藏书。 |

交接时读回调研文档，没有重复联网刷新；来源访问条件后续可能变化，接入前需重核。

## Next steps

1. 读完整调研，特别是“当前项目可以复用什么”“推荐分期”和证据边界；不要重新把 OPDS 当 WebDAV 地址接入。
2. 如果 Master 要求开始接入，确认实际目标：已有 Calibre/Kavita/Komga 实例，还是公共书库；需要哪些语言/书籍/漫画。没有目标实例与授权样本时先完成不依赖账户的目录解析方案，勿猜测私人凭据。
3. 改契约前先读 `app/src/main/java/com/xyreader/core/`。复用现有导入与阅读流程，先设计 OPDS 1.2 来源配置、分页/OpenSearch、获取链接和持久化下载状态，不重写阅读器。
4. 下载目标使用来源 ID + 条目 ID + 格式/版本的唯一命名；确认完成和实际文件格式后再导入。现有 `importSharedFile()` 只接 content/file URI，不能直接传 HTTP URL。
5. 用有权获取的 EPUB 和 CBZ 样本验证：搜索、选择格式、后台下载、完成入库、断网阅读；再测 401/403/429、HTML 登录页伪装文件、空间不足、同名异书、重复点击、取消和进程恢复。
6. 新依赖、Room 迁移、构建/CI 修改或付费资格如确有必要，先按 Master 规则审批。首期不自动注册付费服务或加入未知来源插件市场。

## Decisions & context

- Calibre/Kavita/Komga 访问用户自建藏书，不会自动供应中文新书和连载漫画；必须区分“目录协议”和“内容来源”。
- OPDS acquisition/open-access、borrow、buy 含义不同；检索命中不能等同于整本可下载。首期优先明确授权的直接文件获取。
- Standard Ebooks 完整 OPDS 有 Patrons Circle 等访问资格要求；公共新书 RSS/Atom 不是全库免费 OPDS。
- Gutendex 是第三方元数据 API。Gutenberg 对普通网站自动访问有规则，`formats` URL 不能直接推导批量下载许可。
- Open Library 主要提供书目检索；Internet Archive 必须按条目/文件检查公开获取条件，不承诺所有命中书籍都能下载。
- `LibraryRepositoryImpl.kt` 约第 245 行的 `importSharedFile()` 按清洗文件名落盘，存在跨来源同名覆盖边界，必须在下载层先避免冲突。
- 来源认证可能含 Basic、Digest、API key 或 URL 内 Auth Key；路径、日志、截图和跨主机重定向不能泄漏凭据。目录 XML 禁用外部实体，目录/封面应有大小限制。
- APK 下载管线可参考生命周期处理，但书籍存储、入库与更新安装校验有各自语义，不直接共用 APK 更新器作为书籍下载器。

## Files touched

- `docs/research/2026-10-17-library-catalogs.md`：唯一的书库调研产物，包含候选比较、来源链接、推荐分期和验证边界；尚未提交。
- `.zcode/handoffs/2026-10-17-library-catalogs.md`：本分项交接；总入口为同目录的 `2026-10-17-xy-reader.md`。
- 应用代码、依赖、数据库和构建配置：本方向没有修改。

## Blockers & open questions

- **需 Master 选择（开始实现时）**：真实目标书库或自建服务实例、书籍语言/类型及已有授权文件。当前调研无需补充信息即可审阅。
- **未确认**：Z-Library 官方开发 API、稳定域名、认证和当前下载能力；不能把历史第三方 README 当作实时可用证据。
- **需设备条件**：下载到断网阅读的完整链路须在 Master 实际安装包和网络环境验收；布局和封面观感待 Master 目检。
