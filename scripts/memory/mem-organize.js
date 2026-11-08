// mem-organize.js — 候选 → 记忆（阶段 2，信号打分 + 新鲜度 + 保底，默认 dry-run）
//
// 按零一 2026-11-07 两轮意见：
//  - flags 权重降级（×5，不主导）
//  - 加日期新鲜度：<=3 天 +3，<=14 天 +1
//  - 可操作内容（命令/路径/版本/IP）+3
//  - 保底：最近 3 天每天至少前 10 条进清单（防止新讨论被旧数据挤掉）
//  - 不够分的不删，留在 candidates/
//
// 用法：
//   node mem-organize.js            # dry-run：数字 + 清单 + 日期分布 + 误丢样本
//   node mem-organize.js --write    # 写清单里的

const fs = require('fs');
const path = require('path');
const http = require('http');

const HOME = process.env.DSH_HOME || '/data/user/0/cc.bestly.dsh/files/dshhome';
// OpenViking 地址：环境变量 > config.json（gitignored）> 标准默认
const CFG = (() => { try { return JSON.parse(fs.readFileSync(path.join(HOME, 'memory', 'config.json'), 'utf8')); } catch { return {}; } })();
const CAND = path.join(HOME, 'memory', 'candidates');
const BASE = 'viking://user/zero-eight/memories/episodic';
const WRITE = process.argv.includes('--write');
const THRESHOLD = 3;
const PER_DAY_GUARANTEE = 10;
const LIST = 20;

const KEY = fs.existsSync('/data/adb/dsh-tools/.ovkey')
  ? fs.readFileSync('/data/adb/dsh-tools/.ovkey', 'utf8').trim()
  : '';

function call(method, p, body) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const req = http.request({
      host: process.env.OV_HOST || CFG.ov_host || '127.0.0.1', port: parseInt(process.env.OV_PORT || CFG.ov_port || '1933', 10), path: p, method,
      headers: Object.assign(
        { 'X-API-Key': KEY, 'Content-Type': 'application/json' },
        data ? { 'Content-Length': Buffer.byteLength(data) } : {}
      ),
    }, (r) => {
      let b = '';
      r.on('data', (c) => (b += c));
      r.on('end', () => resolve(b));
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

function loadCandidates() {
  const rows = [];
  if (!fs.existsSync(CAND)) return rows;
  for (const f of fs.readdirSync(CAND)) {
    if (!f.endsWith('.jsonl')) continue;
    for (const line of fs.readFileSync(path.join(CAND, f), 'utf8').split('\n')) {
      if (!line.trim()) continue;
      try { rows.push(JSON.parse(line)); } catch { /* skip */ }
    }
  }
  return rows;
}

function dayOf(ms) {
  const d = new Date(ms || 0);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function isNoise(r) {
  const u = r.user || '';
  if (/^【(信箱|值守|警报|零一→零八|演练)/.test(u)) return true;
  if (/^【零一[^】]*】/.test(u)) return true;
  if (/—— 零一（Hermes/.test(u)) return true;
  if (/^【TG群.+】（群内新消息转发）机器人「(零一|零八)」/.test(u)) return true;
  if (/^【零一/.test(u)) return true;                                   // 零一直投
  if (/Time sampled|Additional instructions|Browser time zone/.test(u)) return true;
  return false;
}

function scoreOf(r) {
  const u = r.user || '';
  const a = r.assistant || '';
  const text = u + '\n' + a;
  let s = 0;
  const fl = Array.isArray(r.flags) ? r.flags.length : 0;
  s += Math.min(fl * 5, 10);
  if (r.toolFail > 0 && (r.user || '').length >= 10) s += 5; // 寒暄轮的工具失败不算「这条踩坑」
  if (/`[^`]+`/.test(text)) s += 1;                                        // 代码块太常见，只 +1
  if (/systemctl|Error:|100\.\d+\.\d+|v\d+\.\d+/.test(text)) s += 2;   // 报错/服务/IP/版本
  if (/\/data\//.test(text)) s += 1;                                      // 路径太常见，只 +1
  if (/就这样|定了|不要|必须|以后|记住/.test(u)) s += 2;
  if (text.length > 500) s += 1;
  if (/^(在\?|在吗|在 |试试|你好|hi|hello)/i.test(u.trim()) || u.length < 8 || (/在不在|这条例行通知/.test(u) && u.length < 60)) s -= 5;
  return s;
}

// 新鲜度只做排序权重，不参与达标判断（否则最近 3 天全部 +3，阈值 3 失效）
function freshnessOf(r) {
  const days = (Date.now() - (r.ts || 0)) / 86400000;
  if (days <= 3) return 3;
  if (days <= 14) return 1;
  return 0;
}

function fmtTime(ms) {
  if (!ms) return '';
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function slug(ts) {
  const d = new Date(ts || Date.now());
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`;
}

function mdOf(r) {
  const when = fmtTime(r.ts);
  return `---
kind: episodic
status: active
confidence: confirmed
source:
  type: session
  ref: "session ${r.session} seq ${r.seq} turn ${r.turn} ws ${r.ws}"
created: ${new Date(r.ts || Date.now()).toISOString()}
---
## ${when}

**${r.user}**

---
${r.assistant}
`;
}

(async () => {
  const rows = loadCandidates();
  const pass1 = [];
  const noiseSamples = [];
  const longSamples = [];
  let skipEmpty = 0, skipShort = 0, skipLong = 0, skipNoise = 0;

  for (const r of rows) {
    const u = r.user || '';
    const a = r.assistant || '';
    if (!u && !a) { skipEmpty++; continue; }
    if (u.length + a.length < 20) { skipShort++; continue; }
    // 不再因总长丢整条：采集器已把 user/assistant 各自截断到 2000
    r.user = (u || '').slice(0, 2000);
    r.assistant = (a || '').slice(0, 2000);
    if (isNoise(r)) {
      skipNoise++;
      if (noiseSamples.length < 2) noiseSamples.push({ ts: fmtTime(r.ts), u: u.slice(0, 50) });
      continue;
    }
    r._score = scoreOf(r);
    r._fresh = freshnessOf(r);
    pass1.push(r);
  }

  const over = pass1.filter((r) => r._score >= THRESHOLD);

  const days = [...new Set(pass1.map((r) => dayOf(r.ts)))].sort().reverse().slice(0, 3);
  const guaranteed = [];
  for (const d of days) {
    const dayRows = pass1
      .filter((r) => dayOf(r.ts) === d)
      .filter((r) => r._score >= 1 && ((r.user || '').length >= 10 || (r.assistant || '').length >= 100))
      .sort((x, y) => y._score - x._score)
      .slice(0, PER_DAY_GUARANTEE);
    guaranteed.push(...dayRows);
  }

  const map = new Map();
  for (const r of [...over, ...guaranteed]) map.set(r.session + ':' + r.seq, r);
  const keep = [...map.values()].sort((x, y) => (y._score + y._fresh) - (x._score + x._fresh) || (x.ts || 0) - (y.ts || 0));

  const byDay = {};
  for (const r of pass1) byDay[dayOf(r.ts)] = (byDay[dayOf(r.ts)] || 0) + 1;
  const keepByDay = {};
  for (const r of keep) keepByDay[dayOf(r.ts)] = (keepByDay[dayOf(r.ts)] || 0) + 1;

  console.log(JSON.stringify({
    mode: WRITE ? 'write' : 'dry-run',
    candidates: rows.length,
    pass1: pass1.length,
    skip: { empty: skipEmpty, short: skipShort, long: skipLong, noise: skipNoise },
    threshold: THRESHOLD,
    overThreshold: over.length,
    guaranteed: guaranteed.length,
    keep: keep.length,
    allByDay: byDay,
    keepByDay,
    last3days: days,
  }, null, 2));

  console.log('\n# 清单（前 ' + Math.min(keep.length, LIST) + ' 条，一行一条）\n');
  keep.slice(0, LIST).forEach((r, i) => {
    console.log(`${i + 1}. ${(r.session || '').slice(0, 10)}#${r.seq} | ${fmtTime(r.ts)} | ${r._score}分 | ${(r.user || '').replace(/\n/g, ' ').slice(0, 40)}`);
  });

  console.log('\n# 误丢抽查（noise 样本 2 / long 样本 2）');
  noiseSamples.forEach((s) => console.log(`  noise | ${s.ts} | ${s.u}`));
  longSamples.forEach((s) => console.log(`  long  | ${s.ts} | ${s.u}`));

  if (!WRITE) {
    console.log(`\n(dry-run：没写。--write 写清单里 ${keep.length} 条；不够分的留在 candidates/。)`);
    return;
  }
  if (!KEY) { console.error('no .ovkey'); process.exit(1); }

  let written = 0, batch = 0;
  for (const r of keep) {
    const uri = `${BASE}/${slug(r.ts)}-${r.seq}.md`;
    const out = await call('POST', '/api/v1/content/write', { uri, content: mdOf(r), mode: 'create' });
    let j = null;
    try { j = JSON.parse(out); } catch { /* ignore */ }
    if (j && j.status === 'ok') {
      written++;
      batch++;
      if (batch >= 100) {
        const c = await call('POST', '/api/v1/snapshot/commit', {
          message: `mem-organize: batch ${written}`,
          paths: ['user/zero-eight/memories/episodic'],
        });
        console.log('commit:', (c || '').slice(0, 120));
        batch = 0;
      }
    } else {
      console.log('skip', uri.split('/').pop(), (out || '').slice(0, 100));
    }
  }
  if (batch > 0) {
    const c = await call('POST', '/api/v1/snapshot/commit', {
      message: `mem-organize: final ${written}`,
      paths: ['user/zero-eight/memories/episodic'],
    });
    console.log('final commit:', (c || '').slice(0, 120));
  }
  console.log(JSON.stringify({ written }));
})().catch((e) => { console.error('ERR', e.message); process.exit(1); });
