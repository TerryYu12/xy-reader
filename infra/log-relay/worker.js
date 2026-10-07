/**
 * 日志中转 Worker（Cloudflare Workers · ES module · 单文件 · 无依赖）
 *
 * 链路：客户端（用户在设置里填写问题描述后主动提交）→ 本 Worker → 开发者的私有 GitHub 仓库。
 * 客户端不持有任何密钥；写仓库用的 GitHub PAT 只存在于 Worker 的 secret（GITHUB_TOKEN）里。
 * 一个 Worker 可同时服务多个应用：env.APPS（JSON 字符串）把应用标识映射到各自的日志仓库，
 * 例如 {"xy-reader":"TerryYu12/xy-reader-feedback"}。
 *
 * 接口：
 *   POST /upload   Content-Type: application/json
 *     请求体 {"app","version","platform","note","log"}
 *     成功   200 {"ok":true,"id":"yyyyMMdd-HHmmss-xxxx"}
 *     失败   4xx/5xx {"ok":false,"error":"<错误码>"}
 *   OPTIONS *      CORS 预检，204
 *
 * 错误码：invalid_json / invalid_app / invalid_version / invalid_platform / invalid_log /
 *         note_too_long / not_found / log_too_large / rate_limited / daily_quota_exceeded /
 *         github_upload_failed / internal_error
 *
 * 环境（见 wrangler.toml）：
 *   GITHUB_TOKEN    secret，fine-grained PAT，只授权日志仓库的 Contents + Issues 读写
 *   APPS            var，应用标识 → "owner/repo" 的 JSON 映射
 *   UPLOAD_LIMITER  可选，Workers Rate Limiting 绑定：按来访 IP 限流（未绑定则不限流）
 *   QUOTA + DAILY_QUOTA  可选，KV 绑定 + 每日全局上限（二者都配置才启用）
 */

// ───────────────────────────── 常量 ─────────────────────────────

/** note（问题描述）最大字符数，与客户端输入框上限一致 */
const MAX_NOTE_CHARS = 2000;
/** log 最大 UTF-8 字节数（客户端自行裁到 2.9MB，这里是兜底） */
const MAX_LOG_BYTES = 3 * 1024 * 1024;
/** 整个请求体上限：log 经 JSON 转义后会略大于原文，留足余量；只用于避免把超大请求整个读进内存 */
const MAX_BODY_BYTES = 16 * 1024 * 1024;
const MAX_VERSION_CHARS = 64;
const MAX_PLATFORM_CHARS = 32;

const ISSUE_LABEL = 'log-upload';
/** issue 正文总长上限（GitHub 硬上限 65536，留余量） */
const ISSUE_BODY_LIMIT = 60000;
/** issue 里内嵌的日志尾部行数 */
const ISSUE_TAIL_LINES = 200;

const GITHUB_API = 'https://api.github.com';
const GITHUB_API_VERSION = '2022-11-28';
const USER_AGENT = 'xy-reader-log-relay';

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Access-Control-Max-Age': '86400',
};

// ───────────────────────────── 入口 ─────────────────────────────

export default {
  async fetch(request, env, ctx) {
    try {
      return await handle(request, env, ctx);
    } catch (err) {
      // 只记录异常本身，绝不输出请求内容或 token
      console.error('[log-relay] internal_error', err && err.stack ? err.stack : String(err));
      return jsonResponse(502, { ok: false, error: 'internal_error' });
    }
  },
};

async function handle(request, env, _ctx) {
  if (request.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: CORS_HEADERS });
  }

  const url = new URL(request.url);
  if (request.method !== 'POST' || url.pathname !== '/upload') {
    return jsonResponse(404, { ok: false, error: 'not_found' });
  }

  // ── 限流（按来访 IP；限流器自身出错时放行，不能因为它挂了而拒绝所有用户）──
  if (env.UPLOAD_LIMITER) {
    const key = request.headers.get('CF-Connecting-IP') || 'unknown';
    let allowed = true;
    try {
      const { success } = await env.UPLOAD_LIMITER.limit({ key });
      allowed = success !== false;
    } catch (err) {
      console.warn('[log-relay] rate limiter failed, letting request through:', String(err));
    }
    if (!allowed) return jsonResponse(429, { ok: false, error: 'rate_limited' });
  }

  // ── 可选日配额：先检查（读），通过校验后再计数（写），无效请求不占名额 ──
  const quota = await readDailyQuota(env);
  if (quota && quota.used >= quota.limit) {
    return jsonResponse(429, { ok: false, error: 'daily_quota_exceeded' });
  }

  // ── 读取并解析请求体 ──
  const declaredLength = Number(request.headers.get('Content-Length') || 0);
  if (declaredLength > MAX_BODY_BYTES) {
    return jsonResponse(413, { ok: false, error: 'log_too_large' });
  }
  const rawBody = await request.text();
  if (rawBody.length > MAX_BODY_BYTES) {
    return jsonResponse(413, { ok: false, error: 'log_too_large' });
  }
  let payload;
  try {
    payload = JSON.parse(rawBody);
  } catch (_) {
    return jsonResponse(400, { ok: false, error: 'invalid_json' });
  }

  // ── 校验 ──
  const apps = parseApps(env);
  const checked = validate(payload, apps);
  if (checked.error) {
    return jsonResponse(checked.status, { ok: false, error: checked.error });
  }
  const { app, version, platform, note, logBytes } = checked;
  const repo = apps[app];
  if (typeof repo !== 'string' || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo)) {
    // APPS 里登记的仓库不是 owner/repo：属于部署配置错误
    throw new Error(`APPS 中 ${app} 的仓库配置无效`);
  }
  await commitDailyQuota(env, quota);

  // ── 第二道脱敏（与客户端 LogRedactor 等价），再落库 ──
  const log = redact(payload.log);
  const safeNote = redact(note);
  const bytes = log === payload.log ? logBytes : new TextEncoder().encode(log);

  const now = new Date();
  const id = generateId(now);
  const path = `logs/${now.getUTCFullYear()}-${pad(now.getUTCMonth() + 1)}/${id}.log`;

  // ── 写日志文件（失败 → 502，不回传 GitHub 的原始响应）──
  if (!env.GITHUB_TOKEN) throw new Error('未配置 GITHUB_TOKEN（wrangler secret put GITHUB_TOKEN）');
  const logUrl = await putLogFile(env, repo, path, id, { app, platform, version }, bytes);
  if (!logUrl) {
    return jsonResponse(502, { ok: false, error: 'github_upload_failed' });
  }

  // ── 建 issue 便于检索；失败只记日志，不影响返回 ──
  const display = {
    app,
    platform: sanitizeInline(platform, 32),
    version: sanitizeInline(version, 32),
  };
  await createIssue(env, repo, {
    title: `[日志] ${display.app} ${display.platform} v${display.version} ${id}`,
    body: buildIssueBody({ ...display, id, note: safeNote, logUrl, log }),
  });

  return jsonResponse(200, { ok: true, id });
}

// ───────────────────────────── 响应 / 工具 ─────────────────────────────

function jsonResponse(status, body) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      ...CORS_HEADERS,
    },
  });
}

function pad(n, width = 2) {
  return String(n).padStart(width, '0');
}

function isNonEmptyString(v) {
  return typeof v === 'string' && v.trim() !== '';
}

/** 解析 env.APPS；缺失或格式错误按「没有任何应用」处理（请求会得到 invalid_app） */
function parseApps(env) {
  try {
    const parsed = JSON.parse(env.APPS || '{}');
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed;
  } catch (err) {
    console.error('[log-relay] APPS 不是合法 JSON：', String(err));
  }
  return {};
}

/**
 * 校验请求体字段。返回 {error, status} 或 {app, version, platform, note, logBytes}。
 * 顺序固定：invalid_json → invalid_app → invalid_version → invalid_platform →
 * note（非字符串 invalid_json / 过长 note_too_long）→ invalid_log → log_too_large。
 */
function validate(payload, apps) {
  const fail = (status, error) => ({ status, error });
  if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) {
    return fail(400, 'invalid_json');
  }
  const { app, version, platform } = payload;
  if (typeof app !== 'string' || !Object.prototype.hasOwnProperty.call(apps, app)) {
    return fail(400, 'invalid_app');
  }
  if (!isNonEmptyString(version) || version.length > MAX_VERSION_CHARS) {
    return fail(400, 'invalid_version');
  }
  if (!isNonEmptyString(platform) || platform.length > MAX_PLATFORM_CHARS) {
    return fail(400, 'invalid_platform');
  }
  let note = payload.note;
  if (note === undefined) note = '';
  if (typeof note !== 'string') return fail(400, 'invalid_json');
  if (note.length > MAX_NOTE_CHARS) return fail(400, 'note_too_long');
  if (!isNonEmptyString(payload.log)) return fail(400, 'invalid_log');
  // UTF-16 码元数 ≤ UTF-8 字节数：字符数已超限就不必再编码
  if (payload.log.length > MAX_LOG_BYTES) return fail(413, 'log_too_large');
  const logBytes = new TextEncoder().encode(payload.log);
  if (logBytes.length > MAX_LOG_BYTES) return fail(413, 'log_too_large');
  return { app, version, platform, note, logBytes };
}

/** 去掉控制字符 / 反引号并截断，用于 issue 标题与正文里的行内展示 */
function sanitizeInline(value, maxChars) {
  const cleaned = String(value)
    .replace(/[\u0000-\u001f\u007f`]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return cleaned.length > maxChars ? `${cleaned.slice(0, maxChars)}…` : cleaned;
}

/** 编号 yyyyMMdd-HHmmss-xxxx：UTC 时间 + 4 位随机 [a-z0-9] */
function generateId(now) {
  const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789';
  const random = crypto.getRandomValues(new Uint8Array(4));
  let suffix = '';
  for (const byte of random) suffix += alphabet[byte % alphabet.length];
  const date = `${now.getUTCFullYear()}${pad(now.getUTCMonth() + 1)}${pad(now.getUTCDate())}`;
  const time = `${pad(now.getUTCHours())}${pad(now.getUTCMinutes())}${pad(now.getUTCSeconds())}`;
  return `${date}-${time}-${suffix}`;
}

/**
 * Uint8Array → base64。分块调用 btoa（块长是 3 的倍数，块与块之间无需处理填充），
 * 避免逐字节拼接字符串造成的 CPU 与内存开销。
 */
function bytesToBase64(bytes) {
  const CHUNK = 3 * 8192;
  const parts = [];
  for (let i = 0; i < bytes.length; i += CHUNK) {
    parts.push(btoa(String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK))));
  }
  return parts.join('');
}

// ───────────────────────────── 日配额（可选，近似值） ─────────────────────────────

/**
 * 读取今天（UTC）已用次数。未配置 KV 或 DAILY_QUOTA 时返回 null（不启用）。
 * 注意：KV 的读-改-写不是原子的，且各边缘节点之间最终一致，所以这是「近似」上限，
 * 并发请求可能让实际数量略超 DAILY_QUOTA；它的作用是防止被刷爆，而不是精确计费。
 * KV 出错时放行（返回 null），理由同限流器。
 */
async function readDailyQuota(env) {
  const limit = Number.parseInt(env.DAILY_QUOTA, 10);
  if (!env.QUOTA || !Number.isFinite(limit) || limit <= 0) return null;
  const key = `quota:${new Date().toISOString().slice(0, 10)}`;
  try {
    const used = Number.parseInt((await env.QUOTA.get(key)) || '0', 10);
    return { key, limit, used: Number.isFinite(used) ? used : 0 };
  } catch (err) {
    console.warn('[log-relay] quota read failed, letting request through:', String(err));
    return null;
  }
}

async function commitDailyQuota(env, quota) {
  if (!quota) return;
  try {
    // 过期时间 2 天：跨过 UTC 午夜后旧计数自然消失
    await env.QUOTA.put(quota.key, String(quota.used + 1), { expirationTtl: 2 * 24 * 3600 });
  } catch (err) {
    console.warn('[log-relay] quota write failed:', String(err));
  }
}

// ───────────────────────────── GitHub ─────────────────────────────

function githubHeaders(env) {
  return {
    Authorization: `Bearer ${env.GITHUB_TOKEN}`,
    Accept: 'application/vnd.github+json',
    'User-Agent': USER_AGENT,
    'X-GitHub-Api-Version': GITHUB_API_VERSION,
    'Content-Type': 'application/json',
  };
}

/** 读掉响应体并截短，仅用于 console.error（只进 Worker 日志，不回传给客户端） */
async function briefBody(res) {
  try {
    return (await res.text()).slice(0, 300);
  } catch (_) {
    return '';
  }
}

/** 写入 logs/<yyyy-MM>/<编号>.log；成功返回文件网页链接，失败返回 null */
async function putLogFile(env, repo, path, id, meta, bytes) {
  try {
    const res = await fetch(`${GITHUB_API}/repos/${repo}/contents/${path}`, {
      method: 'PUT',
      headers: githubHeaders(env),
      body: JSON.stringify({
        message: `log ${id} (${sanitizeInline(meta.app, 32)} ${sanitizeInline(meta.platform, 32)} v${sanitizeInline(meta.version, 32)})`,
        content: bytesToBase64(bytes),
      }),
    });
    if (!res.ok) {
      console.error(`[log-relay] 写入日志失败 HTTP ${res.status}`, await briefBody(res));
      return null;
    }
    let htmlUrl = null;
    try {
      htmlUrl = (await res.json())?.content?.html_url ?? null;
    } catch (_) {
      // 响应体不是预期 JSON：文件已写入，链接退化为 HEAD 分支地址
    }
    return typeof htmlUrl === 'string' && htmlUrl ? htmlUrl : `https://github.com/${repo}/blob/HEAD/${path}`;
  } catch (err) {
    console.error('[log-relay] 写入日志异常', String(err));
    return null;
  }
}

/** 建 issue：先带 label，失败去掉 label 再试一次；仍失败只记日志（日志文件已经安全落库） */
async function createIssue(env, repo, { title, body }) {
  const attempt = async (withLabel) => {
    const payload = { title, body };
    if (withLabel) payload.labels = [ISSUE_LABEL];
    const res = await fetch(`${GITHUB_API}/repos/${repo}/issues`, {
      method: 'POST',
      headers: githubHeaders(env),
      body: JSON.stringify(payload),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status} ${await briefBody(res)}`);
    await res.body?.cancel();
  };
  try {
    await attempt(true);
    return true;
  } catch (err) {
    console.error('[log-relay] 建 issue（带 label）失败，去掉 label 重试：', String(err));
  }
  try {
    await attempt(false);
    return true;
  } catch (err) {
    console.error('[log-relay] 建 issue 失败（日志文件已写入）：', String(err));
    return false;
  }
}

/**
 * issue 正文：应用 / 平台 / 版本 / 问题描述 / 日志文件链接 / 最后 200 行，整体 ≤ ISSUE_BODY_LIMIT。
 * 问题描述与日志都放进代码块：用户文本不会被当成 Markdown 渲染，也不会触发 @提及。
 */
function buildIssueBody({ app, platform, version, id, note, logUrl, log }) {
  const head = [
    `- 应用：${app}`,
    `- 平台：${platform}`,
    `- 版本：${version}`,
    `- 编号：${id}`,
    `- 日志文件：${logUrl}`,
    '',
    '### 问题描述',
    '',
  ].join('\n');
  const noteBlock = fenced(note.trim() === '' ? '（未填写）' : note);
  const tailTitle = `\n\n### 日志（最后 ${ISSUE_TAIL_LINES} 行）\n\n`;
  const tail = lastLines(log, ISSUE_TAIL_LINES);

  const assemble = (keepChars) => {
    let shown = tail;
    let truncated = false;
    if (shown.length > keepChars) {
      let from = shown.length - keepChars;
      // 不把代理对劈成两半，并尽量从行首开始
      if (isLowSurrogate(shown.charCodeAt(from))) from += 1;
      const lineStart = shown.indexOf('\n', from);
      shown = lineStart !== -1 && lineStart - from < 2000 ? shown.slice(lineStart + 1) : shown.slice(from);
      truncated = true;
    }
    return `${head}${noteBlock}${tailTitle}${truncated ? '（过长，已从头部截断）\n\n' : ''}${fenced(shown)}\n`;
  };

  // 先按「总长上限减去固定部分」估算，围栏等开销超出时再收紧一次
  let keep = Math.max(0, ISSUE_BODY_LIMIT - head.length - noteBlock.length - tailTitle.length - 64);
  let body = assemble(keep);
  for (let i = 0; i < 3 && body.length > ISSUE_BODY_LIMIT; i += 1) {
    keep = Math.max(0, keep - (body.length - ISSUE_BODY_LIMIT) - 16);
    body = assemble(keep);
  }
  return body;
}

/** 取文本最后 n 行（从尾部向前找换行，不对整份日志 split） */
function lastLines(text, n) {
  let end = text.length;
  if (text.endsWith('\n')) end -= 1;
  let pos = end;
  for (let i = 0; i < n; i += 1) {
    const idx = text.lastIndexOf('\n', pos - 1);
    if (idx === -1) return text.slice(0, end);
    pos = idx;
  }
  return text.slice(pos + 1, end);
}

/** 用比内容里最长反引号串更长的围栏包裹文本，保证内容无法提前闭合代码块 */
function fenced(text) {
  let longest = 0;
  for (const run of text.match(/`+/g) || []) longest = Math.max(longest, run.length);
  const fence = '`'.repeat(Math.max(3, longest + 1));
  return `${fence}text\n${text}\n${fence}`;
}

function isLowSurrogate(code) {
  return code >= 0xdc00 && code <= 0xdfff;
}

// ───────────────────────────── 脱敏（与客户端 LogRedactor 保持等价） ─────────────────────────────
//
// 客户端 app/src/main/java/com/xyreader/feedback/LogRedactor.kt 是规则的原件，这里逐条移植；
// 正则文本两边完全一致（只用 Java 与 JS 语义相同的子集：显式字符类、不依赖 \s \b \w、
// 固定宽度的单字符后行断言）。改规则时两边必须同步，并同步更新两边的测试向量。

const MASK = '***';
const MAX_URL_PATH_CHARS = 80;

/** Authorization / Cookie（含 Set-Cookie、Proxy-Authorization）头：从冒号后到行尾整段遮蔽 */
const RE_HEADER = new RegExp(String.raw`(authorization|cookie)([ \t]*:[ \t]*)[^ \t\r\n][^\r\n]*`, 'gi');

/** Bearer xxx / Basic xxx */
const RE_SCHEME = new RegExp(String.raw`(?<![A-Za-z0-9_])(bearer|basic)([ \t]+)[A-Za-z0-9\-._~+/]+=*`, 'gi');

/**
 * key=value / key: value / JSON "key":"value"（也覆盖 access_token、client_secret、apiKey 等以敏感词结尾的键）。
 * 值依次尝试：转义双引号 \"..\"、双引号、单引号、无引号；带引号的分支允许缺少收尾引号。
 * 引号内的转义序列最多按转义语义解析 MAX_ESCAPES_IN_VALUE 次，之后当普通字符吃到收尾引号
 * （与 Kotlin 侧一致：java.util.regex 对组的重复是递归实现，转义个数不设上限会栈溢出）。
 */
const MAX_ESCAPES_IN_VALUE = 64;
const RE_KEY_VALUE = new RegExp(
  String.raw`(password|passwd|pwd|token|secret|api[_-]?key|authorization|cookie)` +
    String.raw`(\\?["']?[ \t]*[:=][ \t]*)` +
    String.raw`(\\"[^\\\r\n]*(?:\\[^"\r\n][^\\\r\n]*){0,${MAX_ESCAPES_IN_VALUE}}(?:\\"|[^\r\n]*)` +
    String.raw`|"[^"\\\r\n]*(?:\\[^\r\n][^"\\\r\n]*){0,${MAX_ESCAPES_IN_VALUE}}[^"\r\n]*"?` +
    String.raw`|'[^'\\\r\n]*(?:\\[^\r\n][^'\\\r\n]*){0,${MAX_ESCAPES_IN_VALUE}}[^'\r\n]*'?` +
    String.raw`|[^ \t\r\n,;&)\]}"'\\]+)`,
  'gi',
);

/** Google 令牌形态：ya29. 访问令牌 / 1// 刷新令牌 / GOCSPX- 客户端密钥 */
const RE_GOOGLE = new RegExp(String.raw`(?<![A-Za-z0-9])(ya29\.|1//|GOCSPX-)[A-Za-z0-9._\-]+`, 'gi');

/** scheme://authority/path?query#fragment */
const RE_URL = new RegExp(
  String.raw`(?<![A-Za-z0-9+.\-])([A-Za-z][A-Za-z0-9+.\-]*)://([^ \t\r\n/?#"'<>]*)([^ \t\r\n?#"'<>]*)` +
    String.raw`(?:\?[^ \t\r\n#"'<>]*)?(?:#[^ \t\r\n"'<>]*)?`,
  'g',
);

/** 邮箱 */
const RE_EMAIL = new RegExp(
  String.raw`(?<![A-Za-z0-9._%+\-])[A-Za-z0-9._%+\-]{1,64}@([A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+)`,
  'g',
);

/** 键值里被遮蔽的值：保留引号形态；空引号串本来就没有内容，原样返回 */
function maskKeyValue(value) {
  if (value.startsWith('\\"')) {
    const closed = value.length >= 4 && value.endsWith('\\"');
    const inner = value.length - 2 - (closed ? 2 : 0);
    return inner <= 0 ? value : `\\"${MASK}${closed ? '\\"' : ''}`;
  }
  const quote = value[0];
  if (quote === '"' || quote === "'") {
    const closed = value.length >= 2 && value.endsWith(quote);
    const inner = value.length - 1 - (closed ? 1 : 0);
    return inner <= 0 ? value : `${quote}${MASK}${closed ? quote : ''}`;
  }
  return MASK;
}

/** 去掉 userinfo、query、fragment，path 截到 80 字符 */
function maskUrl(scheme, authority, path) {
  const host = authority.slice(authority.lastIndexOf('@') + 1);
  let end = MAX_URL_PATH_CHARS;
  if (path.length > end) {
    // 不把代理对劈成两半
    const code = path.charCodeAt(end - 1);
    if (code >= 0xd800 && code <= 0xdbff) end -= 1;
    path = `${path.slice(0, end)}…`;
  }
  return `${scheme}://${host}${path}`;
}

/**
 * 脱敏一段文本（可含多行，规则都不会跨行）。
 * 预检：每条规则只有在文本里出现它的触发词时才执行，纯属性能优化（Workers 有 CPU 时间上限），
 * 不影响结果——这些规则只会删除内容或插入 ***，不会凭空造出新的触发词。
 */
function redact(text) {
  if (typeof text !== 'string' || text === '') return text;
  const lower = text.toLowerCase();
  const has = (...needles) => needles.some((n) => lower.includes(n));
  let s = text;
  if (has('authorization', 'cookie')) {
    s = s.replace(RE_HEADER, (_m, key, sep) => key + sep + MASK);
  }
  if (has('bearer', 'basic')) {
    s = s.replace(RE_SCHEME, (_m, scheme, space) => scheme + space + MASK);
  }
  if (has('password', 'passwd', 'pwd', 'token', 'secret', 'key', 'authorization', 'cookie')) {
    s = s.replace(RE_KEY_VALUE, (_m, key, sep, value) => key + sep + maskKeyValue(value));
  }
  if (has('ya29.', '1//', 'gocspx-')) {
    s = s.replace(RE_GOOGLE, (_m, prefix) => prefix + MASK);
  }
  if (lower.includes('://')) {
    s = s.replace(RE_URL, (_m, scheme, authority, path) => maskUrl(scheme, authority, path));
  }
  if (lower.includes('@')) {
    s = s.replace(RE_EMAIL, (_m, domain) => `${MASK}@${domain}`);
  }
  return s;
}
