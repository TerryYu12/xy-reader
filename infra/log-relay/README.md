# 日志中转 Worker（log-relay）

XY reader 的「BUG 反馈」链路里负责**转交日志**的 Cloudflare Worker：单文件、无依赖、不持有任何客户端密钥，
只拿一个**只能写日志仓库**的 GitHub PAT，把用户主动提交的日志写进开发者的私有仓库，再把编号交还给用户。

```
Android 客户端                    本 Worker                         私有 GitHub 仓库
─────────────                  ─────────────                      ─────────────────
设置 → BUG 反馈
  填写问题描述 ──POST /upload──▶ 限流 / 日配额 / 校验 / 二次脱敏
  （日志只在本机，                 │
   提交时才一次性上传）            ├─ PUT logs/<yyyy-MM>/<编号>.log ─▶ 日志文件
                                  └─ POST issues（label: log-upload）▶ issue（含问题描述与最后 200 行）
  显示编号并复制 ◀──{"ok":true,"id":"<编号>"}──┘
        │
        └─ 用户把编号发给开发者 ──▶ 开发者用编号取回日志（见下文「按编号取回日志」）
```

- 客户端平时只在本地记日志（`cacheDir/logs/`，最多约 2MB，已脱敏），**不会自动上传**；
- 编号形如 `20261007-123456-ab12`（`yyyyMMdd-HHmmss-xxxx`，UTC 时间 + 4 位随机字符）；
- 脱敏有三道：本地写盘前、上传前（整份日志）、本 Worker 入库前（规则与客户端等价）。

## 目录

| 文件 | 说明 |
| --- | --- |
| `worker.js` | Worker 本体（ES module，`export default { fetch }`） |
| `wrangler.toml` | 部署配置：域名、`APPS` 映射、限流绑定、可选 KV 日配额 |
| `test/worker.test.mjs` | 测试（`node --test`，mock GitHub API，无需网络） |
| `package.json` | 仅用来声明 ESM 与 `npm test`，**没有任何依赖** |

## 接口

`POST /upload`，`Content-Type: application/json`：

```json
{ "app": "xy-reader", "version": "0.5.3", "platform": "android", "note": "问题描述（≤ 2000 字）", "log": "日志全文（UTF-8 ≤ 3MB）" }
```

成功 `200 {"ok":true,"id":"20261007-123456-ab12"}`；失败 `{"ok":false,"error":"<码>"}`：

| 错误码 | HTTP | 含义 |
| --- | --- | --- |
| `invalid_json` | 400 | 请求体不是 JSON 对象，或 `note` 不是字符串 |
| `invalid_app` | 400 | `app` 不在 `APPS` 里 |
| `invalid_version` / `invalid_platform` | 400 | 不是非空字符串（长度上限 64 / 32） |
| `note_too_long` | 400 | 问题描述超过 2000 字符 |
| `invalid_log` | 400 | `log` 缺失或为空 |
| `not_found` | 404 | 只接受 `POST /upload` |
| `log_too_large` | 413 | 日志超过 3MB（UTF-8 字节数） |
| `rate_limited` | 429 | 同一 IP 提交太频繁 |
| `daily_quota_exceeded` | 429 | 触达每日全局上限 |
| `github_upload_failed` | 502 | 写日志文件失败（不回传 GitHub 的原始响应，细节只在 Worker 日志里） |
| `internal_error` | 502 | 未捕获异常（含部署配置错误，如未设置 `GITHUB_TOKEN`） |

`OPTIONS` 返回 `204` 与 CORS 头；所有响应都带 `Access-Control-Allow-Origin: *`。

## 部署

### 1. 建日志仓库

1. 在 GitHub 新建一个**私有**仓库，建议名 `xy-reader-feedback`（与 `wrangler.toml` 里 `APPS` 的默认值一致）；
2. 在该仓库建一个 label：`log-upload`（Issues → Labels → New label）。Worker 建 issue 时会带上它，
   便于用 `gh issue list -l log-upload` 检索；label 不存在或失败时 Worker 会去掉 label 重试，不影响上传。

### 2. 建 fine-grained PAT（只给这一个仓库）

GitHub → Settings → Developer settings → Personal access tokens → **Fine-grained tokens** → Generate：

- Repository access：**Only select repositories** → 只选上面的日志仓库；
- Repository permissions：**Contents: Read and write**、**Issues: Read and write**（其余保持 No access）；
- **一定设置过期时间**（如 90 天），并在日历里记下续期日期，见下文「安全与限制」。

### 3. 登录并设置 secret

```bash
cd infra/log-relay
npx wrangler login --device        # 无浏览器 / 远程环境用设备码登录；本机有浏览器可去掉 --device
npx wrangler secret put GITHUB_TOKEN   # 粘贴上一步的 PAT（不会回显，也不会写进任何文件）
```

### 4. 按需修改 `wrangler.toml`

- `routes`：改成你自己的域名（该域名所属的 zone 要托管在你的 Cloudflare 账号里）；
- `[vars] APPS`：应用标识 → `owner/repo`；
- `[[ratelimits]]`：默认每个 IP 每 60 秒最多 2 次上传；
- 想要每日全局上限：取消 KV 与 `DAILY_QUOTA` 的注释（先 `npx wrangler kv namespace create QUOTA` 拿到 id）。

### 5. 部署

```bash
npx wrangler deploy
```

### 6. 自测

先把变量设成你自己的域名（`wrangler.toml` 的 `routes` 里写的那个）：

```bash
RELAY=https://xylog.terry12.ccwu.cc

# 正常上传：应返回 {"ok":true,"id":"20261007-123456-ab12"}，并在仓库里看到日志文件与 issue
curl -sS "$RELAY/upload" \
  -H 'Content-Type: application/json' \
  -d '{"app":"xy-reader","version":"0.0.0-selftest","platform":"curl","note":"部署自测","log":"hello from curl\n"}'

# CORS 预检：应返回 204，并带 access-control-allow-origin: *
curl -si -X OPTIONS "$RELAY/upload" \
  -H 'Origin: https://example.com' -H 'Access-Control-Request-Method: POST'

# 限流：连发 3 次，第 3 次应是 429 rate_limited（Cloudflare 限流是按数据中心近似计数，偶尔会多放行一两次）
for i in 1 2 3; do
  curl -s -o /dev/null -w '%{http_code}\n' "$RELAY/upload" \
    -H 'Content-Type: application/json' \
    -d '{"app":"xy-reader","version":"0.0.0-selftest","platform":"curl","note":"限流自测","log":"x\n"}'
done
```

排查：`npx wrangler tail` 实时查看 Worker 日志（GitHub 返回 401 / 403 / 404 时会在这里留下原因，不会返回给客户端）。

### 7. 客户端端点

Android 客户端的上传地址只在 **`app/build.gradle.kts` 的 `FEEDBACK_ENDPOINT`** 里定义一次
（`buildConfigField("String", "FEEDBACK_ENDPOINT", ...)`）。Worker 的域名与默认值
`https://xylog.terry12.ccwu.cc/upload` 不同时，只改这一处，重新打包即可。

## 按编号取回日志

用户把编号（如 `20261007-123456-ab12`）发给你后，日志文件在 `logs/<yyyy-MM>/<编号>.log`，
年月取自编号前 6 位（`202610` → `2026-10`）。用 [GitHub CLI](https://cli.github.com/) 取回：

```bash
gh api -H "Accept: application/vnd.github.raw+json" \
  repos/<owner>/<repo>/contents/logs/2026-10/20261007-123456-ab12.log > bug.log
```

> **必须带 `Accept: application/vnd.github.raw+json`**。不带时 Contents API 返回的是 JSON 包装（内容是 base64），
> 而且超过 1MB 的文件根本不会返回内容；带上 raw 头才会直接返回文件原文（最大 100MB）。

按 label 列出最近的上传（每条 issue 标题就是 `[日志] <应用> <平台> v<版本> <编号>`，正文里有问题描述、文件链接和最后 200 行）：

```bash
gh issue list -R <owner>/<repo> -l log-upload
gh issue view -R <owner>/<repo> <issue 编号>
```

## 一个 Worker 服务多个应用

`APPS` 是「应用标识 → `owner/repo`」的 JSON 映射，客户端请求体里的 `app` 必须是其中一个键。新增应用：

1. 为新应用建一个私有日志仓库（和上面一样建 `log-upload` label）；
2. 在 `wrangler.toml` 的 `APPS` 里加一项，例如
   `'{"xy-reader":"TerryYu12/xy-reader-feedback","lite-music":"TerryYu12/lite-music-feedback"}'`；
3. 把 PAT 的 Repository access 加上新仓库（fine-grained PAT 可以同时授权多个仓库；改授权不需要重新生成 token）；
4. `npx wrangler deploy`，新应用的客户端把请求体里的 `app` 设成对应的标识。

需要知道的风险与限制：

- **一个 PAT 授权了多个仓库，泄露时影响面是这些仓库的 Contents + Issues**。不放心的话，为每个应用单独部署一个 Worker（各用各的 PAT）；
- 应用标识不是秘密——任何人都可以冒用它向对应仓库上传（和「任何人可上传」是同一类风险，见下节）；
- 限流计数按来访 IP 而不是按应用，日配额是全局的，多个应用共用同一份额度。

## 安全与限制

- **端点是公开的，任何人都可以向它上传**。防护靠：每 IP 限流（默认 2 次/分钟）、可选的每日全局上限、
  单次 3MB 上限、`APPS` 白名单、入库前再脱敏。KV 日配额是近似值（读-改-写非原子、最终一致），只用来防被刷爆，不是精确计数。
  即便如此仍可能被灌入垃圾：留意仓库体积与 issue 数量，必要时临时收紧限流或下线路由。
- **PAT 会过期**。过期后所有上传都会返回 `502 github_upload_failed`（`npx wrangler tail` 里能看到 `HTTP 401`）。
  续期：重新生成 PAT → `npx wrangler secret put GITHUB_TOKEN`，无需重新部署。
- **仓库会膨胀**。每次上传最多 3MB，GitHub 仓库建议保持在几 GB 以内：定期清理旧月份目录（`logs/<yyyy-MM>/`）和已处理的 issue。
- **隐私**。日志在客户端写盘前、上传前和本 Worker 入库前各脱敏一遍（密码 / 令牌 / Authorization / Cookie / 链接 query / 邮箱），
  但脱敏是兜底，不保证万无一失；日志仓库必须保持私有，PAT 只授权必要的仓库与权限。问题描述与日志在 issue 里都放进代码块，
  不会被当成 Markdown 渲染，也不会触发 `@提及`。
- **CPU 时间**。Worker 对一次上传要做 JSON 解析、脱敏、编码、base64，约 30–60 ms CPU / MB 日志（本机 V8 实测，仅供参考）。
  Workers **Free 计划单次请求只有 10 ms CPU**，较大的日志可能超限（客户端看到的是 Cloudflare 的 1102 错误页，会被判为 `invalid_response`）；
  正式使用建议开通 Workers Paid，或在日志特别大时让用户先清理缓存再提交。
- **日志里不要出现秘密**。脱敏规则覆盖常见形态（见 `LogRedactor.kt` 的 KDoc），代码里记录日志时依然不要写账号密码、令牌与书名全文。

## 脱敏规则要保持两边一致

Worker 里 `worker.js` 末尾的 `redact` 是 Android 侧
`app/src/main/java/com/xyreader/feedback/LogRedactor.kt` 的逐条移植（正则文本一致）。改规则时：

1. 先改 `LogRedactor.kt` 与 `LogRedactorTest.kt`；
2. 同步改 `worker.js` 的 `redact` 与 `test/worker.test.mjs` 里的 `REDACTION_VECTORS`（两边的测试向量必须一致）。

## 开发与测试

```bash
cd infra/log-relay
npm test      # 即 node --test，要求 Node ≥ 20；不访问网络，GitHub API 全部被 mock
```

本地用 wrangler 跑一遍真实运行时（`wrangler dev`）时，把 `GITHUB_TOKEN=...` 写进 `infra/log-relay/.dev.vars`
（已在 `.gitignore` 里，绝不要入库）。
