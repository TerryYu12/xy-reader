# 0.5.4 BUG 反馈日志链路 — 验证记录（2026-10-07）

## 需求

> 「开子代理把 bug 反馈也落实一下」（附《Lite Music 反馈日志链路：架构与移植说明》）

把 Lite Music 的反馈链路移植到 XY reader：
- 客户端平时只在本地记日志；
- 用户在「设置 → BUG 反馈」提交后，日志才发给不持有密钥的 Cloudflare Worker；
- Worker 写入私有仓库 `TerryYu12/xy-reader-feedback`，并返回一个编号；
- 开发者凭编号取回日志。

部署方式由用户选定：为 XY reader 单独部署一个 Worker，暂定域名 `xylog.terry12.ccwu.cc`。

## 实现

| 部分 | 文件 | 要点 |
|---|---|---|
| 本地日志 | `feedback/AppLog.kt`、`feedback/AppLogFile.kt` | `ArkApp.onCreate` 第一行初始化。同时写 logcat 与文件 `cacheDir/logs/app.log`，超过 1MB 轮转到 `.1`。单条消息上限 2000 字符，每秒最多 200 条，内存保留最近 500 行。崩溃栈同步落盘。原有 46 处 `Log.*` 调用改走 AppLog，另在打开书失败、扫描结束、检查更新失败处补了日志 |
| 脱敏 | `feedback/LogRedactor.kt` | 遮蔽以下内容：Authorization / Cookie 头、Bearer / Basic 凭据、password / token / secret 等键值、Google 令牌（`ya29.`、`1//`、`GOCSPX-`）、URL 中的 userinfo 与 query（path 截到 80 字符）、邮箱 |
| 上传 | `feedback/FeedbackReport.kt`、`feedback/FeedbackUploader.kt` | 上传内容依次为设备信息头、本地日志、当前进程最近 500 行 logcat，整体再脱敏一遍，总量控制在 2.9MB 以内。用 OkHttp POST，超时 30 秒。错误码一一映射成中文文案。端点只在 `app/build.gradle.kts` 的 `FEEDBACK_ENDPOINT` 一处定义 |
| 界面 | `ui/FeedbackDialog.kt`、`ui/SettingsScreen.kt` | 设置页新增「BUG 反馈」入口。问题描述必填，最多 2000 字，弹窗里写明会上传哪些内容。成功后显示编号并自动复制；失败后可重试 |
| 隐私 | `PRIVACY*.md`、`ui/PrivacyScreen.kt`、README | 网络通信部分补上「检查更新」与「BUG 反馈」，删掉原来「不进行任何网络通信」等与事实不符的说法 |
| Worker | `infra/log-relay/` | 单文件、无依赖的 Cloudflare Worker，支持 app → 仓库映射，详见下方说明 |
| CI | `.github/workflows/android-ci.yml` | `paths-ignore` 加入 `infra/**`，只改 Worker 时不触发 Android 构建 |

Worker 的处理流程：
- 预检请求返回 CORS 头；
- 限流：同一 IP 每 60 秒最多 2 次；另可选按天的全局上限，基于 KV；
- 校验请求字段；
- 写库前在 Worker 端再脱敏一遍；
- 用 Contents API 写入 `logs/<yyyy-MM>/<编号>.log`；
- 创建 issue 并打上 `log-upload` 标签，打标签失败时去掉标签重试一次；
- 部署步骤见 `infra/log-relay/README.md`。

## 验证

- **Worker**：`npm test` 共 54 项，Node 20 与 22 上全部通过。另外做了两项实际运行检查：
  - `wrangler deploy --dry-run` 通过；
  - 本地用 `wrangler dev` 起了真实的 workerd，确认 OPTIONS 返回 204、未知路径返回 404、参数校验正确、第 3 次连续请求返回 429、带假 token 写 GitHub 返回 502。
- **Android**：[CI #25](https://github.com/TerryYu12/xy-reader/actions/runs/37586918852)（commit `f476022`）中单元测试与正式签名的 Release 构建都通过。APK 工件为 `XY-READER-0.5.4`，22,350,063 bytes。
  - CI #24 曾失败：`FeedbackUploaderTest` 用了 `com.sun.net.httpserver`，而 Android 单测的类路径里没有这个包。改用 `java.net.ServerSocket` 写的本地服务后通过。
- **新增测试**：`LogRedactorTest`、`AppLogFileTest`、`FeedbackUploaderTest`、`FeedbackReportTest`、`AppLogTest`。
- **跨端一致性**：Kotlin 与 JS 两套脱敏规则在 12 万行随机语料上输出完全一致。

## 部署与上线（用户执行）

1. 私有仓库 `TerryYu12/xy-reader-feedback` 与 `log-upload` 标签已建好。
2. 创建 fine-grained PAT，只授权该仓库的 Contents 与 Issues 读写权限，并设置过期时间。
3. 在 `infra/log-relay/` 下依次执行 `npx wrangler login --device`、`npx wrangler secret put GITHUB_TOKEN`、`npx wrangler deploy`。
4. 用 curl 自测三项：正常上传、预检请求、连发触发 429。
5. 真机上提交一次反馈，拿到编号后，用 `gh api -H "Accept: application/vnd.github.raw+json" …` 取回日志。

## 已知限制

- 任何拿到地址的人都能上传，目前只靠 IP 限流挡住刷量；可按需开启 KV 日配额。
- Cloudflare 免费版每次请求的 CPU 上限是 10ms，本机实测处理 1MB 日志约需 30–60ms，大日志可能超限，必要时换付费版。
- PAT 过期后，所有上传都会返回 `github_upload_failed`，需要到期前续期。
- 上传地址写死在客户端里；域名失效时只能发新版修复。
- Worker 部署之前，App 内提交反馈会提示网络错误。
- 部署和真机验证尚未执行。
