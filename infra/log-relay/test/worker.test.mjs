// 日志中转 Worker 的测试：node --test（无依赖）。
// 用 mock 的 globalThis.fetch 拦截对 GitHub API 的调用，构造 Request 直接调用 fetch handler。
import { afterEach, beforeEach, describe, mock, test } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../worker.js';

// ───────────────────────────── 测试脚手架 ─────────────────────────────

const REPO = 'TerryYu12/xy-reader-feedback';
const OTHER_REPO = 'TerryYu12/lite-music-feedback';
const APPS = JSON.stringify({ 'xy-reader': REPO, 'lite-music': OTHER_REPO });
const FIXED_NOW = '2026-10-07T12:34:56.789Z';
const ID_PATTERN = /^\d{8}-\d{6}-[a-z0-9]{4}$/;

const baseEnv = (extra = {}) => ({ APPS, GITHUB_TOKEN: 'ghp_test_token', ...extra });

const validBody = (over = {}) => ({
  app: 'xy-reader',
  version: '0.5.3',
  platform: 'android',
  note: '打开 PDF 闪退',
  log: '2026-10-07 10:00:00.000 I/Tag: hello\n',
  ...over,
});

function uploadRequest(body, { method = 'POST', path = '/upload', ip = '203.0.113.7', headers = {} } = {}) {
  const init = { method, headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': ip, ...headers } };
  if (method !== 'GET' && method !== 'HEAD') {
    init.body = typeof body === 'string' ? body : JSON.stringify(body);
  }
  return new Request(`https://xylog.example.com${path}`, init);
}

/** 调用 Worker，返回 {res, text, json} */
async function invoke(request, env = baseEnv()) {
  const res = await worker.fetch(request, env, { waitUntil() {}, passThroughOnException() {} });
  const text = await res.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    // 非 JSON 响应（204 等）
  }
  return { res, text, json };
}

let githubCalls;
let realFetch;

/** 安装 GitHub API 的 mock，返回请求记录。handlers.put / handlers.issue 可覆盖默认的 201 响应 */
function mockGithub(handlers = {}) {
  githubCalls = [];
  globalThis.fetch = async (url, init = {}) => {
    const call = {
      url: String(url),
      method: init.method ?? 'GET',
      headers: init.headers ?? {},
      body: init.body ? JSON.parse(init.body) : null,
    };
    githubCalls.push(call);
    if (call.method === 'PUT') return (handlers.put ?? defaultPut)(call);
    if (call.method === 'POST') return (handlers.issue ?? defaultIssue)(call);
    throw new Error(`未预期的请求 ${call.method} ${call.url}`);
  };
  return githubCalls;
}

const defaultPut = (call) =>
  new Response(
    JSON.stringify({ content: { html_url: `https://github.com/${REPO}/blob/main/${call.url.split('/contents/')[1]}` } }),
    { status: 201 },
  );
const defaultIssue = () => new Response(JSON.stringify({ number: 1 }), { status: 201 });

const puts = () => githubCalls.filter((c) => c.method === 'PUT');
const issues = () => githubCalls.filter((c) => c.method === 'POST');
const decodeContent = (call) => Buffer.from(call.body.content, 'base64').toString('utf8');

/** 把 Date 固定在某个时刻（new Date() 与 Date.now() 都生效），跑完恢复 */
async function withFixedDate(iso, fn) {
  const RealDate = Date;
  const fixed = new RealDate(iso).getTime();
  globalThis.Date = class extends RealDate {
    constructor(...args) {
      if (args.length === 0) super(fixed);
      else super(...args);
    }
    static now() {
      return fixed;
    }
  };
  try {
    return await fn();
  } finally {
    globalThis.Date = RealDate;
  }
}

function memoryKv({ failGet = false } = {}) {
  const store = new Map();
  return {
    store,
    puts: [],
    async get(key) {
      if (failGet) throw new Error('KV 暂时不可用');
      return store.has(key) ? store.get(key) : null;
    },
    async put(key, value, options) {
      store.set(key, value);
      this.puts.push({ key, value, options });
    },
  };
}

function assertCors(res) {
  assert.equal(res.headers.get('access-control-allow-origin'), '*');
  assert.match(res.headers.get('access-control-allow-methods'), /POST/);
  assert.match(res.headers.get('access-control-allow-methods'), /OPTIONS/);
  assert.match(res.headers.get('access-control-allow-headers'), /Content-Type/i);
}

beforeEach(() => {
  realFetch = globalThis.fetch;
  mockGithub();
  mock.method(console, 'error', () => {});
  mock.method(console, 'warn', () => {});
});

afterEach(() => {
  globalThis.fetch = realFetch;
  mock.restoreAll();
});

// ───────────────────────────── CORS 与路由 ─────────────────────────────

describe('CORS 与路由', () => {
  test('OPTIONS 返回 204 与 CORS 头', async () => {
    const { res, text } = await invoke(uploadRequest(null, { method: 'OPTIONS' }));
    assert.equal(res.status, 204);
    assert.equal(text, '');
    assertCors(res);
  });

  test('任意路径的 OPTIONS 预检都放行', async () => {
    const { res } = await invoke(uploadRequest(null, { method: 'OPTIONS', path: '/anything' }));
    assert.equal(res.status, 204);
  });

  test('只接受 POST /upload，其余 404 not_found', async () => {
    const cases = [
      { method: 'GET', path: '/upload' },
      { method: 'POST', path: '/' },
      { method: 'POST', path: '/upload/extra' },
      { method: 'POST', path: '/other' },
      { method: 'GET', path: '/' },
      { method: 'PUT', path: '/upload' },
      { method: 'DELETE', path: '/upload' },
    ];
    for (const c of cases) {
      const { res, json } = await invoke(uploadRequest(validBody(), c));
      assert.equal(res.status, 404, `${c.method} ${c.path}`);
      assert.deepEqual(json, { ok: false, error: 'not_found' });
      assertCors(res);
    }
    assert.equal(githubCalls.length, 0);
  });

  test('所有响应都带 CORS 头、JSON 内容类型', async () => {
    const responses = [
      await invoke(uploadRequest(validBody())),
      await invoke(uploadRequest('not json')),
      await invoke(uploadRequest(validBody(), { path: '/nope' })),
      await invoke(uploadRequest(validBody({ app: 'ghost' }))),
    ];
    for (const { res } of responses) {
      assertCors(res);
      assert.match(res.headers.get('content-type'), /application\/json/);
    }
  });
});

// ───────────────────────────── 请求校验 ─────────────────────────────

describe('请求校验', () => {
  const reject = async (body, status, error, env) => {
    const { res, json } = await invoke(uploadRequest(body), env);
    assert.equal(res.status, status, JSON.stringify(json));
    assert.deepEqual(json, { ok: false, error });
    assert.equal(githubCalls.length, 0, '校验失败时不应访问 GitHub');
  };

  test('invalid_json：不是 JSON / 不是对象', async () => {
    await reject('not json', 400, 'invalid_json');
    await reject('', 400, 'invalid_json');
    await reject('[]', 400, 'invalid_json');
    await reject('null', 400, 'invalid_json');
    await reject('"str"', 400, 'invalid_json');
    await reject('123', 400, 'invalid_json');
  });

  test('invalid_app：缺失 / 未登记 / 非字符串 / 原型链上的键', async () => {
    await reject(validBody({ app: 'ghost' }), 400, 'invalid_app');
    await reject(validBody({ app: undefined }), 400, 'invalid_app');
    await reject(validBody({ app: 123 }), 400, 'invalid_app');
    await reject(validBody({ app: '' }), 400, 'invalid_app');
    await reject(validBody({ app: 'constructor' }), 400, 'invalid_app');
    await reject(validBody({ app: '__proto__' }), 400, 'invalid_app');
    await reject(validBody({ app: 'toString' }), 400, 'invalid_app');
  });

  test('invalid_app：APPS 缺失或不是合法 JSON 时按没有任何应用处理', async () => {
    await reject(validBody(), 400, 'invalid_app', { GITHUB_TOKEN: 't' });
    await reject(validBody(), 400, 'invalid_app', { GITHUB_TOKEN: 't', APPS: '{oops' });
    await reject(validBody(), 400, 'invalid_app', { GITHUB_TOKEN: 't', APPS: '[]' });
  });

  test('invalid_version / invalid_platform', async () => {
    await reject(validBody({ version: '' }), 400, 'invalid_version');
    await reject(validBody({ version: '   ' }), 400, 'invalid_version');
    await reject(validBody({ version: undefined }), 400, 'invalid_version');
    await reject(validBody({ version: 5 }), 400, 'invalid_version');
    await reject(validBody({ version: 'v'.repeat(65) }), 400, 'invalid_version');
    await reject(validBody({ platform: '' }), 400, 'invalid_platform');
    await reject(validBody({ platform: undefined }), 400, 'invalid_platform');
    await reject(validBody({ platform: ['android'] }), 400, 'invalid_platform');
    await reject(validBody({ platform: 'p'.repeat(33) }), 400, 'invalid_platform');
  });

  test('note：2000 字符可以，2001 → note_too_long，非字符串 → invalid_json，缺省按空处理', async () => {
    const ok = await invoke(uploadRequest(validBody({ note: 'n'.repeat(2000) })));
    assert.equal(ok.res.status, 200);
    mockGithub(); // 清空上面成功请求留下的记录
    await reject(validBody({ note: 'n'.repeat(2001) }), 400, 'note_too_long');
    await reject(validBody({ note: 12 }), 400, 'invalid_json');
    await reject(validBody({ note: null }), 400, 'invalid_json');
    await reject(validBody({ note: { a: 1 } }), 400, 'invalid_json');

    mockGithub();
    const body = validBody();
    delete body.note;
    const missing = await invoke(uploadRequest(body));
    assert.equal(missing.res.status, 200);
    assert.match(issues()[0].body.body, /（未填写）/);
  });

  test('invalid_log：缺失 / 空 / 纯空白 / 非字符串', async () => {
    await reject(validBody({ log: '' }), 400, 'invalid_log');
    await reject(validBody({ log: '  \n\t ' }), 400, 'invalid_log');
    await reject(validBody({ log: undefined }), 400, 'invalid_log');
    await reject(validBody({ log: null }), 400, 'invalid_log');
    await reject(validBody({ log: ['a'] }), 400, 'invalid_log');
  });

  test('log_too_large：按 UTF-8 字节数计，3MB 整可以，多一个字节就拒绝', async () => {
    const limit = 3 * 1024 * 1024;
    await reject(validBody({ log: 'a'.repeat(limit + 1) }), 413, 'log_too_large');
    // 每个「中」3 字节：字符数远小于 3M，但字节数超限
    await reject(validBody({ log: '中'.repeat(1_100_000) }), 413, 'log_too_large');
    // 恰好 3MB 字节：1048576 个「中」
    const exactly = await invoke(uploadRequest(validBody({ log: '中'.repeat(1_048_576) })));
    assert.equal(exactly.res.status, 200);
    mockGithub();
    await reject(validBody({ log: `${'中'.repeat(1_048_576)}a` }), 413, 'log_too_large');
  });

  test('超大 Content-Length 不读取请求体，直接 413', async () => {
    const request = uploadRequest(validBody(), { headers: { 'Content-Length': String(64 * 1024 * 1024) } });
    // 部分运行时会重写 Content-Length；只有头真的保留下来才断言
    if (request.headers.get('content-length') === String(64 * 1024 * 1024)) {
      const { res, json } = await invoke(request);
      assert.equal(res.status, 413);
      assert.deepEqual(json, { ok: false, error: 'log_too_large' });
    }
  });
});

// ───────────────────────────── 限流 ─────────────────────────────

describe('限流（UPLOAD_LIMITER）', () => {
  test('未通过 → 429 rate_limited，且不访问 GitHub；按 CF-Connecting-IP 计数', async () => {
    const seen = [];
    const limiter = {
      async limit(arg) {
        seen.push(arg);
        return { success: false };
      },
    };
    const { res, json } = await invoke(uploadRequest(validBody(), { ip: '198.51.100.9' }), baseEnv({ UPLOAD_LIMITER: limiter }));
    assert.equal(res.status, 429);
    assert.deepEqual(json, { ok: false, error: 'rate_limited' });
    assertCors(res);
    assert.deepEqual(seen, [{ key: '198.51.100.9' }]);
    assert.equal(githubCalls.length, 0);
  });

  test('没有 CF-Connecting-IP 头时用固定 key', async () => {
    const seen = [];
    const limiter = {
      async limit(arg) {
        seen.push(arg);
        return { success: true };
      },
    };
    const request = new Request('https://xylog.example.com/upload', { method: 'POST', body: JSON.stringify(validBody()) });
    const { res } = await invoke(request, baseEnv({ UPLOAD_LIMITER: limiter }));
    assert.equal(res.status, 200);
    assert.deepEqual(seen, [{ key: 'unknown' }]);
  });

  test('通过 → 正常上传', async () => {
    const limiter = { limit: async () => ({ success: true }) };
    const { res } = await invoke(uploadRequest(validBody()), baseEnv({ UPLOAD_LIMITER: limiter }));
    assert.equal(res.status, 200);
  });

  test('限流在校验之前：被限流的无效请求也是 429', async () => {
    const limiter = { limit: async () => ({ success: false }) };
    const { res, json } = await invoke(uploadRequest('not json'), baseEnv({ UPLOAD_LIMITER: limiter }));
    assert.equal(res.status, 429);
    assert.equal(json.error, 'rate_limited');
  });

  test('限流器自身抛错时放行，并记录警告', async () => {
    const limiter = {
      async limit() {
        throw new Error('limiter down');
      },
    };
    const { res, json } = await invoke(uploadRequest(validBody()), baseEnv({ UPLOAD_LIMITER: limiter }));
    assert.equal(res.status, 200);
    assert.match(json.id, ID_PATTERN);
    assert.ok(console.warn.mock.callCount() >= 1);
  });
});

// ───────────────────────────── 日配额 ─────────────────────────────

describe('日配额（QUOTA + DAILY_QUOTA）', () => {
  test('超出 → 429 daily_quota_exceeded；按 UTC 日期计数；次日重置', async () => {
    const kv = memoryKv();
    const env = baseEnv({ QUOTA: kv, DAILY_QUOTA: '2' });

    await withFixedDate('2026-10-07T23:59:00Z', async () => {
      assert.equal((await invoke(uploadRequest(validBody()), env)).res.status, 200);
      assert.equal((await invoke(uploadRequest(validBody()), env)).res.status, 200);
      const third = await invoke(uploadRequest(validBody()), env);
      assert.equal(third.res.status, 429);
      assert.deepEqual(third.json, { ok: false, error: 'daily_quota_exceeded' });
      assertCors(third.res);
    });
    assert.equal(kv.store.get('quota:2026-10-07'), '2');
    assert.equal(puts().length, 2, '超额的请求不应写入 GitHub');
    assert.ok(kv.puts[0].options.expirationTtl >= 86400, '计数键应带过期时间');

    await withFixedDate('2026-10-08T00:00:01Z', async () => {
      assert.equal((await invoke(uploadRequest(validBody()), env)).res.status, 200);
    });
    assert.equal(kv.store.get('quota:2026-10-08'), '1');
  });

  test('无效请求不占用名额', async () => {
    const kv = memoryKv();
    const env = baseEnv({ QUOTA: kv, DAILY_QUOTA: '1' });
    await withFixedDate(FIXED_NOW, async () => {
      assert.equal((await invoke(uploadRequest('garbage'), env)).res.status, 400);
      assert.equal((await invoke(uploadRequest(validBody({ app: 'ghost' })), env)).res.status, 400);
      assert.equal((await invoke(uploadRequest(validBody({ log: '' })), env)).res.status, 400);
      assert.equal(kv.store.size, 0);
      assert.equal((await invoke(uploadRequest(validBody()), env)).res.status, 200);
      assert.equal((await invoke(uploadRequest(validBody()), env)).res.status, 429);
    });
  });

  test('只绑了 KV 没设 DAILY_QUOTA，或 DAILY_QUOTA 不是正整数 → 不启用', async () => {
    for (const quota of [undefined, '', 'abc', '0', '-5']) {
      const kv = memoryKv();
      const env = baseEnv({ QUOTA: kv, ...(quota === undefined ? {} : { DAILY_QUOTA: quota }) });
      for (let i = 0; i < 3; i += 1) {
        assert.equal((await invoke(uploadRequest(validBody()), env)).res.status, 200, `DAILY_QUOTA=${quota}`);
      }
      assert.equal(kv.store.size, 0);
    }
  });

  test('只设了 DAILY_QUOTA 没绑 KV → 不启用', async () => {
    const env = baseEnv({ DAILY_QUOTA: '1' });
    for (let i = 0; i < 3; i += 1) {
      assert.equal((await invoke(uploadRequest(validBody()), env)).res.status, 200);
    }
  });

  test('KV 读失败时放行', async () => {
    const env = baseEnv({ QUOTA: memoryKv({ failGet: true }), DAILY_QUOTA: '1' });
    assert.equal((await invoke(uploadRequest(validBody()), env)).res.status, 200);
    assert.ok(console.warn.mock.callCount() >= 1);
  });

  test('限流与配额都启用时：先限流', async () => {
    const env = baseEnv({
      UPLOAD_LIMITER: { limit: async () => ({ success: false }) },
      QUOTA: memoryKv(),
      DAILY_QUOTA: '100',
    });
    const { json } = await invoke(uploadRequest(validBody()), env);
    assert.equal(json.error, 'rate_limited');
  });
});

// ───────────────────────────── 成功路径 ─────────────────────────────

describe('成功路径', () => {
  test('写入日志文件、建 issue、返回编号', async () => {
    await withFixedDate(FIXED_NOW, async () => {
      const log = '2026-10-07 10:00:00.000 I/Tag: hello\n2026-10-07 10:00:01.000 W/Tag: 世界\n';
      const { res, json } = await invoke(uploadRequest(validBody({ log })));

      assert.equal(res.status, 200);
      assert.deepEqual(Object.keys(json), ['ok', 'id']);
      assert.equal(json.ok, true);
      assert.match(json.id, ID_PATTERN);
      assert.ok(json.id.startsWith('20261007-123456-'), json.id);
      assertCors(res);

      // 日志文件：PUT /repos/{repo}/contents/logs/<yyyy-MM>/<编号>.log
      assert.equal(puts().length, 1);
      const put = puts()[0];
      assert.equal(put.url, `https://api.github.com/repos/${REPO}/contents/logs/2026-10/${json.id}.log`);
      assert.equal(decodeContent(put), log, 'base64 内容可解码回（脱敏后的）日志');
      assert.match(put.body.message, new RegExp(json.id));

      // GitHub 请求头
      assert.equal(put.headers.Authorization, 'Bearer ghp_test_token');
      assert.equal(put.headers.Accept, 'application/vnd.github+json');
      assert.equal(put.headers['User-Agent'], 'xy-reader-log-relay');
      assert.equal(put.headers['X-GitHub-Api-Version'], '2022-11-28');

      // issue
      assert.equal(issues().length, 1);
      const issue = issues()[0];
      assert.equal(issue.url, `https://api.github.com/repos/${REPO}/issues`);
      assert.equal(issue.body.title, `[日志] xy-reader android v0.5.3 ${json.id}`);
      assert.deepEqual(issue.body.labels, ['log-upload']);
      assert.equal(issue.headers.Authorization, 'Bearer ghp_test_token');
      assert.equal(issue.headers['User-Agent'], 'xy-reader-log-relay');
    });
  });

  test('issue 正文含 应用/平台/版本/问题描述/日志文件链接/日志尾部', async () => {
    const log = '日志第一行\n日志第二行\n日志最后一行\n';
    const { json } = await invoke(uploadRequest(validBody({ note: '阅读器翻页卡顿', log })));
    const body = issues()[0].body.body;
    assert.match(body, /应用：xy-reader/);
    assert.match(body, /平台：android/);
    assert.match(body, /版本：0\.5\.3/);
    assert.match(body, new RegExp(`编号：${json.id}`));
    assert.match(body, new RegExp(`日志文件：https://github\\.com/${REPO}/blob/main/logs/\\d{4}-\\d{2}/${json.id}\\.log`));
    assert.match(body, /阅读器翻页卡顿/);
    assert.match(body, /日志（最后 200 行）/);
    assert.ok(body.includes('日志第一行\n日志第二行\n日志最后一行'));
    assert.ok(body.length <= 60000);
  });

  test('编号：yyyyMMdd-HHmmss-xxxx（UTC + 4 位 [a-z0-9]），连续生成互不相同', async () => {
    const ids = new Set();
    await withFixedDate('2026-01-02T03:04:05Z', async () => {
      for (let i = 0; i < 30; i += 1) {
        const { json } = await invoke(uploadRequest(validBody()));
        assert.match(json.id, /^20260102-030405-[a-z0-9]{4}$/);
        ids.add(json.id);
      }
    });
    assert.ok(ids.size >= 25, `随机后缀应基本不重复，实际 ${ids.size}/30`);
  });

  test('路径里的年月取 UTC：跨年时刻', async () => {
    await withFixedDate('2026-12-31T23:59:59Z', async () => {
      const { json } = await invoke(uploadRequest(validBody()));
      assert.equal(puts()[0].url.split('/contents/')[1], `logs/2026-12/${json.id}.log`);
    });
  });

  test('多应用：app 决定写入哪个仓库', async () => {
    const { res } = await invoke(uploadRequest(validBody({ app: 'lite-music', platform: 'android' })));
    assert.equal(res.status, 200);
    assert.ok(puts()[0].url.startsWith(`https://api.github.com/repos/${OTHER_REPO}/contents/`));
    assert.equal(issues()[0].url, `https://api.github.com/repos/${OTHER_REPO}/issues`);
    assert.match(issues()[0].body.title, /^\[日志\] lite-music android v0\.5\.3 /);
  });

  test('issue 标题里的 version / platform 会被清理（换行、反引号、超长）', async () => {
    await invoke(uploadRequest(validBody({ version: '1.0\n`x`\u0007', platform: 'a  b' })));
    const title = issues()[0].body.title;
    assert.ok(!/[\n`\u0007]/.test(title), JSON.stringify(title));
    assert.match(title, /^\[日志\] xy-reader a b v1\.0 x /);
  });

  test('日志里的 UTF-8 多字节与 emoji 往返无损；分块 base64 在块边界上正确', async () => {
    const unit = '漫画 reader 日志😀 line\n';
    const base = unit.repeat(2000);
    // 24576 = 3 × 8192 是分块长度；覆盖块边界前后与多块
    const sizes = [1, 2, 3, 24_575, 24_576, 24_577, 49_152, 49_153, 70_000, 200_000];
    for (const size of sizes) {
      const log = Buffer.from(base, 'utf8').subarray(0, size).toString('utf8') + 'END\n';
      mockGithub();
      const { res } = await invoke(uploadRequest(validBody({ log })));
      assert.equal(res.status, 200);
      assert.equal(decodeContent(puts()[0]), log, `size=${size}`);
    }
  });

  test('3MB 级日志也能完整写入', async () => {
    const line = '2026-10-07 10:00:00.000 D/Tag: 一行普通的日志内容，没有任何敏感信息 0123456789\n';
    const log = line.repeat(Math.floor((3 * 1024 * 1024 - 1024) / Buffer.byteLength(line)));
    const { res } = await invoke(uploadRequest(validBody({ log })));
    assert.equal(res.status, 200);
    assert.equal(decodeContent(puts()[0]), log);
    assert.ok(issues()[0].body.body.length <= 60000);
  });
});

// ───────────────────────────── issue 细节 ─────────────────────────────

describe('issue 内容与重试', () => {
  test('只内嵌最后 200 行', async () => {
    const log = Array.from({ length: 1000 }, (_, i) => `line-${String(i + 1).padStart(4, '0')}`).join('\n') + '\n';
    await invoke(uploadRequest(validBody({ log })));
    const body = issues()[0].body.body;
    assert.ok(body.includes('line-1000'));
    assert.ok(body.includes('line-0801'));
    assert.ok(!body.includes('line-0800'), '第 800 行不该出现');
    assert.equal((body.match(/line-\d{4}/g) || []).length, 200);
  });

  test('日志不足 200 行时全部内嵌；没有结尾换行也行', async () => {
    await invoke(uploadRequest(validBody({ log: 'a\nb\nc' })));
    assert.ok(issues()[0].body.body.includes('a\nb\nc'));
  });

  test('正文整体不超过 60000 字符：超长单行日志从头部截断，保留最新内容', async () => {
    const log = Array.from({ length: 200 }, (_, i) => `row-${i}-${'x'.repeat(1990)}`).join('\n') + '\nLAST-LINE\n';
    await invoke(uploadRequest(validBody({ log, note: 'n'.repeat(2000) })));
    const body = issues()[0].body.body;
    assert.ok(body.length <= 60000, `正文长度 ${body.length}`);
    assert.ok(body.includes('LAST-LINE'));
    assert.ok(body.includes('已从头部截断'));
  });

  test('反引号：内容里的 ``` 不会提前闭合代码块', async () => {
    await invoke(uploadRequest(validBody({ note: 'see ```code``` here', log: 'a ````` b\nlast\n' })));
    const body = issues()[0].body.body;
    assert.ok(body.includes('````text\nsee ```code``` here\n````'), '问题描述应用 4 个反引号围栏');
    assert.ok(body.includes('``````text\na ````` b\nlast\n\n``````') || body.includes('``````text\na ````` b\nlast\n``````'));
  });

  test('带 label 的 issue 失败 → 去掉 label 重试一次，仍返回成功', async () => {
    let attempt = 0;
    mockGithub({
      issue: () => {
        attempt += 1;
        return attempt === 1
          ? new Response(JSON.stringify({ message: 'Validation Failed' }), { status: 422 })
          : new Response('{}', { status: 201 });
      },
    });
    const { res, json } = await invoke(uploadRequest(validBody()));
    assert.equal(res.status, 200);
    assert.equal(json.ok, true);
    assert.equal(issues().length, 2);
    assert.deepEqual(issues()[0].body.labels, ['log-upload']);
    assert.equal('labels' in issues()[1].body, false, '重试请求不带 labels');
    assert.equal(issues()[1].body.title, issues()[0].body.title);
    assert.equal(issues()[1].body.body, issues()[0].body.body);
    assert.ok(console.error.mock.callCount() >= 1);
  });

  test('issue 两次都失败：只 console.error，仍返回成功（日志文件已落库）', async () => {
    mockGithub({ issue: () => new Response('boom', { status: 500 }) });
    const { res, json } = await invoke(uploadRequest(validBody()));
    assert.equal(res.status, 200);
    assert.match(json.id, ID_PATTERN);
    assert.equal(issues().length, 2);
    assert.ok(console.error.mock.callCount() >= 2);
  });

  test('issue 请求抛网络异常同样重试并最终放行', async () => {
    let attempt = 0;
    mockGithub({
      issue: () => {
        attempt += 1;
        throw new Error('network down');
      },
    });
    const { res } = await invoke(uploadRequest(validBody()));
    assert.equal(res.status, 200);
    assert.equal(attempt, 2);
  });
});

// ───────────────────────────── GitHub 写入失败 ─────────────────────────────

describe('GitHub 写入失败', () => {
  test('PUT 失败 → 502 github_upload_failed，不回传 GitHub 原始响应，也不建 issue', async () => {
    mockGithub({ put: () => new Response('{"message":"Bad credentials","secret":"SHOULD-NOT-LEAK"}', { status: 401 }) });
    const { res, text, json } = await invoke(uploadRequest(validBody()));
    assert.equal(res.status, 502);
    assert.deepEqual(json, { ok: false, error: 'github_upload_failed' });
    assert.ok(!text.includes('SHOULD-NOT-LEAK'));
    assert.ok(!text.includes('Bad credentials'));
    assertCors(res);
    assert.equal(issues().length, 0);
    assert.ok(console.error.mock.callCount() >= 1);
  });

  test('PUT 抛网络异常 → 502 github_upload_failed', async () => {
    mockGithub({
      put: () => {
        throw new TypeError('fetch failed');
      },
    });
    const { res, json } = await invoke(uploadRequest(validBody()));
    assert.equal(res.status, 502);
    assert.deepEqual(json, { ok: false, error: 'github_upload_failed' });
    assert.equal(issues().length, 0);
  });

  test('PUT 成功但响应体不是 JSON：仍视为成功，链接退化为 HEAD 分支地址', async () => {
    mockGithub({ put: () => new Response('created', { status: 201 }) });
    const { res, json } = await invoke(uploadRequest(validBody()));
    assert.equal(res.status, 200);
    assert.match(issues()[0].body.body, new RegExp(`https://github\\.com/${REPO}/blob/HEAD/logs/\\d{4}-\\d{2}/${json.id}\\.log`));
  });

  test('日志里的 token 不会出现在任何对 GitHub 的请求体里之外的地方（响应不含 token）', async () => {
    const { text } = await invoke(uploadRequest(validBody()));
    assert.ok(!text.includes('ghp_test_token'));
  });
});

// ───────────────────────────── 未捕获异常 ─────────────────────────────

describe('internal_error', () => {
  test('未捕获异常 → 502 internal_error（env 为空）', async () => {
    const res = await worker.fetch(uploadRequest(validBody()), null, {});
    const json = await res.json();
    assert.equal(res.status, 502);
    assert.deepEqual(json, { ok: false, error: 'internal_error' });
    assertCors(res);
    assert.ok(console.error.mock.callCount() >= 1);
  });

  test('没配置 GITHUB_TOKEN → 502 internal_error（不是把请求发给 GitHub 再失败）', async () => {
    const { res, json } = await invoke(uploadRequest(validBody()), { APPS });
    assert.equal(res.status, 502);
    assert.equal(json.error, 'internal_error');
    assert.equal(githubCalls.length, 0);
  });

  test('APPS 里的仓库不是 owner/repo 形式 → 502 internal_error', async () => {
    const env = baseEnv({ APPS: JSON.stringify({ 'xy-reader': '../../etc/passwd' }) });
    const { res, json } = await invoke(uploadRequest(validBody()), env);
    assert.equal(res.status, 502);
    assert.equal(json.error, 'internal_error');
    assert.equal(githubCalls.length, 0);
  });

  test('错误响应里不含请求内容或 token', async () => {
    const res = await worker.fetch(uploadRequest(validBody({ note: 'SENSITIVE-NOTE' })), null, {});
    const text = await res.text();
    assert.ok(!text.includes('SENSITIVE-NOTE'));
  });
});

// ───────────────────────────── 脱敏（第二道保险） ─────────────────────────────

/**
 * 脱敏测试向量：[输入, 期望输出]。必须与 Android 侧 LogRedactorTest 保持一致
 * （app/src/test/java/com/xyreader/feedback/LogRedactorTest.kt）——两边规则要等价。
 */
const REDACTION_KEYS = [
  'password', 'passwd', 'pwd', 'token', 'access_token', 'refresh_token', 'id_token',
  'client_secret', 'secret', 'authorization', 'cookie', 'api_key', 'apikey',
];
const longPath = '/' + 'a'.repeat(120);
const path80 = '/' + 'a'.repeat(79);
const REDACTION_VECTORS = [
  // 规则 1：请求头
  ['Authorization: Bearer abc.def.ghi', 'Authorization: ***'],
  ['authorization:Basic dXNlcjpwYXNz', 'authorization:***'],
  [
    '2026-10-07 10:00:00.000 D/OkHttp: Authorization: Bearer eyJhbGciOi.xxx.yyy',
    '2026-10-07 10:00:00.000 D/OkHttp: Authorization: ***',
  ],
  ['Cookie: SID=abc123; theme=dark', 'Cookie: ***'],
  ['Set-Cookie: JSESSIONID=ABC; Path=/; HttpOnly', 'Set-Cookie: ***'],
  ['Proxy-Authorization: Basic Zm9vOmJhcg==', 'Proxy-Authorization: ***'],
  ['Cookie:', 'Cookie:'],
  ['Cookie: ', 'Cookie: '],
  // 规则 2：Bearer / Basic
  ['got token Bearer abc123xyz here', 'got token Bearer *** here'],
  ['bearer abc123', 'bearer ***'],
  ['BEARER   abc==', 'BEARER   ***'],
  ['use Basic dXNlcjpwYXNz for auth', 'use Basic *** for auth'],
  ['Bearer ***', 'Bearer ***'],
  // 规则 3：键值
  ['password=hunter2', 'password=***'],
  ['passwd: hunter2', 'passwd: ***'],
  ['pwd = hunter2', 'pwd = ***'],
  ['PASSWORD=Hunter2&user=bob', 'PASSWORD=***&user=bob'],
  ['Token=abc', 'Token=***'],
  ...REDACTION_KEYS.flatMap((key) => [
    [`${key}=value123`, `${key}=***`],
    [`${key}: value123`, `${key}: ***`],
    [`"${key}":"value123"`, `"${key}":"***"`],
  ]),
  ['clientSecret=abc123', 'clientSecret=***'],
  ['refreshToken=abc123', 'refreshToken=***'],
  ['X-Api-Key: KEY123', 'X-Api-Key: ***'],
  ['API-KEY=KEY123', 'API-KEY=***'],
  ['tokenType=Bearer', 'tokenType=Bearer'],
  ['key: value', 'key: value'],
  ['secretary=alice', 'secretary=alice'],
  ['(password=hunter2)', '(password=***)'],
  ['token=abc,secret=def;pwd=ghi', 'token=***,secret=***;pwd=***'],
  ['password=hunter2&user=bob', 'password=***&user=bob'],
  ['password=', 'password='],
  ['password: ', 'password: '],
  ['{"password":""}', '{"password":""}'],
  ['{"password":"hunter2","user":"bob"}', '{"password":"***","user":"bob"}'],
  ['{"token": "abc def", "x": 1}', '{"token": "***", "x": 1}'],
  ["'password': 'abc def'", "'password': '***'"],
  ['password="abc def"', 'password="***"'],
  ['{"password": 12345}', '{"password": ***}'],
  ['{\\"password\\":\\"hunter2\\",\\"user\\":\\"bob\\"}', '{\\"password\\":\\"***\\",\\"user\\":\\"bob\\"}'],
  ['password="abc', 'password="***'],
  ['authorization=Bearer abc', 'authorization=*** ***'],
  ['{"authorization":"Bearer abc"}', '{"authorization":"***"}'],
  [
    'WebDavConfigEntity(name=nas, baseUrl=https://nas.local/dav, username=me@example.com, password=s3cr3t)',
    'WebDavConfigEntity(name=nas, baseUrl=https://nas.local/dav, username=***@example.com, password=***)',
  ],
  [
    'GoogleDriveAccountEntity(clientId=123.apps.googleusercontent.com, clientSecret=GOCSPX-xyz_123-ABC, refreshToken=1//0abcDEF-ghi_JKL)',
    'GoogleDriveAccountEntity(clientId=123.apps.googleusercontent.com, clientSecret=***, refreshToken=***)',
  ],
  // 规则 4：Google 令牌形态
  ['bad ya29.A0ARrdaM-123_abc.def value', 'bad ya29.*** value'],
  ['saved 1//0gAbCdEf-gh_ij ok', 'saved 1//*** ok'],
  ['secret GOCSPX-abc_123-XYZ end', 'secret GOCSPX-*** end'],
  ['xya29.abc and a1//zzz', 'xya29.abc and a1//zzz'],
  ['Token ya29.abc, then 1//0abc and GOCSPX-abc.', 'Token ya29.***, then 1//*** and GOCSPX-***'],
  // 规则 5：URL
  ['https://user:pass@dav.example.com/dav/?token=abc#frag', 'https://dav.example.com/dav/'],
  ['http://example.com/a/b?x=1&y=2', 'http://example.com/a/b'],
  ['http://example.com?x=1', 'http://example.com'],
  ['http://example.com#frag', 'http://example.com'],
  ['https://user:p@ss@dav.example.com/dav/', 'https://dav.example.com/dav/'],
  ['ftp://user@host/path', 'ftp://host/path'],
  ['webdav://3/漫画/某某书.cbz?x=1', 'webdav://3/漫画/某某书.cbz'],
  [
    'content://com.android.externalstorage.documents/tree/primary%3AComics/document/x#f',
    'content://com.android.externalstorage.documents/tree/primary%3AComics/document/x',
  ],
  ['file:///storage/emulated/0/Download/a.cbz', 'file:///storage/emulated/0/Download/a.cbz'],
  [`https://h.com${path80}`, `https://h.com${path80}`],
  [`https://h.com${longPath}`, `https://h.com${path80}…`],
  [`https://h.com${path80}…`, `https://h.com${path80}…`],
  [`https://h.com/${'a'.repeat(78)}😀tail`, `https://h.com/${'a'.repeat(78)}…`],
  ['"https://a.com/p?q=1" and \'http://b.org/x\'', '"https://a.com/p" and \'http://b.org/x\''],
  ['see (https://a.com/x?y=1) now', 'see (https://a.com/x now'],
  ['a https://x.com/1?t=1 b https://u:p@y.com/2#z', 'a https://x.com/1 b https://y.com/2'],
  [
    '2026-10-07 10:00:00.000 W/WebDavScanner: PROPFIND 失败，跳过目录: https://me@example.com:pw@nas.local/dav/漫画/, HTTP 500',
    '2026-10-07 10:00:00.000 W/WebDavScanner: PROPFIND 失败，跳过目录: https://nas.local/dav/漫画/, HTTP 500',
  ],
  // 规则 6：邮箱
  ['联系 me@example.com 或 a.b+c@sub.example.co.uk 。', '联系 ***@example.com 或 ***@sub.example.co.uk 。'],
  ['username=me@example.com', 'username=***@example.com'],
  ['Foo@1a2b3c object toString', 'Foo@1a2b3c object toString'],
  ['user@localhost only', 'user@localhost only'],
  ['***@example.com', '***@example.com'],
  // 普通文本原样保留
  ['plain text with nothing sensitive', 'plain text with nothing sensitive'],
  [
    '2026-10-07 10:00:00.000 I/LibraryScanner: 仓库扫描结束 新增=3 更新=0 移除=1 耗时=1234ms',
    '2026-10-07 10:00:00.000 I/LibraryScanner: 仓库扫描结束 新增=3 更新=0 移除=1 耗时=1234ms',
  ],
  ['\tat com.xyreader.archive.PdfPageSource.close(PdfPageSource.kt:62)', '\tat com.xyreader.archive.PdfPageSource.close(PdfPageSource.kt:62)'],
  ['java.io.IOException: closed', 'java.io.IOException: closed'],
  ['第 12 页渲染完成，用时 35ms，缓存命中 true', '第 12 页渲染完成，用时 35ms，缓存命中 true'],
  // 一行多处
  [
    'Mixed: password=abc https://u:p@host/x?y=1 me@example.com Bearer abcdef',
    'Mixed: password=*** https://host/x ***@example.com Bearer ***',
  ],
];

describe('脱敏（与客户端 LogRedactor 等价）', () => {
  /** 把一组行作为日志上传，返回写入 GitHub 的（脱敏后）行 */
  async function redactedLinesOf(lines) {
    mockGithub();
    const { res } = await invoke(uploadRequest(validBody({ log: `${lines.join('\n')}\n` })));
    assert.equal(res.status, 200);
    const written = decodeContent(puts()[0]);
    assert.ok(written.endsWith('\n'));
    return written.slice(0, -1).split('\n');
  }

  test('每条测试向量的输出与期望一致', async () => {
    const out = await redactedLinesOf(REDACTION_VECTORS.map(([input]) => input));
    assert.equal(out.length, REDACTION_VECTORS.length);
    REDACTION_VECTORS.forEach(([input, expected], i) => {
      assert.equal(out[i], expected, `输入：${input}`);
    });
  });

  test('幂等：对脱敏结果再脱敏不再变化', async () => {
    const out = await redactedLinesOf(REDACTION_VECTORS.map(([, expected]) => expected));
    REDACTION_VECTORS.forEach(([, expected], i) => {
      assert.equal(out[i], expected, `期望值应是不动点：${expected}`);
    });
  });

  test('多行文本逐行独立：值不会吞到下一行', async () => {
    const out = await redactedLinesOf(['Cookie: a=1', 'next line', 'password=abc', 'ok']);
    assert.deepEqual(out, ['Cookie: ***', 'next line', 'password=***', 'ok']);
  });

  test('CRLF 换行：遮蔽止于 \\r', async () => {
    mockGithub();
    await invoke(uploadRequest(validBody({ log: 'Cookie: a=1\r\nnext line\r\n' })));
    assert.equal(decodeContent(puts()[0]), 'Cookie: ***\r\nnext line\r\n');
  });

  test('note 同样脱敏（issue 正文里看不到明文）', async () => {
    await invoke(uploadRequest(validBody({ note: '登录失败 password=hunter2 邮箱 me@example.com Bearer abc.def' })));
    const body = issues()[0].body.body;
    assert.ok(!body.includes('hunter2'));
    assert.ok(!body.includes('me@example.com'));
    assert.ok(!body.includes('abc.def'));
    assert.match(body, /password=\*\*\*/);
    assert.match(body, /\*\*\*@example\.com/);
  });

  test('issue 里内嵌的日志尾部也是脱敏后的', async () => {
    await invoke(uploadRequest(validBody({ log: 'x\nAuthorization: Bearer topsecret\ny\n' })));
    assert.ok(!issues()[0].body.body.includes('topsecret'));
  });

  test('客户端漏掉的敏感内容在落库前被兜底脱敏', async () => {
    const log = [
      '2026-10-07 10:00:00.000 W/X: 未脱敏的 token=abc123 与 https://u:p@h.com/p?secret=1',
      '10-07 10:00:00.000 1 1 D OkHttp: Cookie: SID=zzz',
    ].join('\n');
    await invoke(uploadRequest(validBody({ log })));
    const written = decodeContent(puts()[0]);
    for (const leaked of ['abc123', 'u:p@', 'secret=1', 'SID=zzz']) {
      assert.ok(!written.includes(leaked), `不应出现 ${leaked}`);
    }
  });

  test('大体积、病态输入不会触发回溯爆炸（3MB 内完成）', async () => {
    const hostile = [
      'password:"'.repeat(250_000),
      '\\'.repeat(1_000_000),
      'a@b.'.repeat(500_000),
      `token="${'a\\"'.repeat(500_000)}`,
    ].join('\n');
    const start = performance.now();
    const { res } = await invoke(uploadRequest(validBody({ log: hostile.slice(0, 3 * 1024 * 1024 - 16) })));
    const elapsed = performance.now() - start;
    assert.equal(res.status, 200);
    assert.ok(elapsed < 15_000, `耗时 ${elapsed.toFixed(0)}ms`);
  });
});
