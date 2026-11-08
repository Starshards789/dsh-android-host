// mem-collect.js — 会话 → 候选（阶段 1，只读会话文件，不碰引擎）
//
// 输入：$DSH_HOME/sessions/<workspace>/session-*/session.v4.jsonl.zstd
// 输出：$DSH_HOME/memory/candidates/YYYY-MM-DD.jsonl（本地日期，不是 UTC）
//
// 依零一 2026-11-07 审阅意见（v3）：
//  - ts 用事件时间 ev.time（不是采集时间）；userTs 单独记
//  - day 用本地时间（避开 UTC 凌晨落错天）
//  - MAX 放宽到 2000（候选是中间产物，整理器再剪）
//  - 加 flags / ws 字段
//  - 一个 turn 多个 user/message 时，后者覆盖前者——这是故意的：只保留本轮最后一条用户输入
//
// 六条旧约束仍成立：只取 text / 双字段判失败 / 逐行 try-catch / 三 workspace / 幂等 (session,seq) / 按天

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const HOME = process.env.DSH_HOME || '/data/user/0/cc.bestly.dsh/files/dshhome';
const SESSIONS = path.join(HOME, 'sessions');
const OUTDIR = path.join(HOME, 'memory', 'candidates');
const MAX = 2000;

function textOf(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return '';
  return content
    .filter((b) => b && b.type === 'text')
    .map((b) => b.text || '')
    .join('\n');
}

function clip(s, n) {
  if (typeof s !== 'string') return '';
  return s.length > n ? s.slice(0, n) + '…' : s;
}

function localDay(d = new Date()) {
  return [
    d.getFullYear(),
    String(d.getMonth() + 1).padStart(2, '0'),
    String(d.getDate()).padStart(2, '0'),
  ].join('-');
}

function walkDirs(dir) {
  const out = [];
  if (!fs.existsSync(dir)) return out;
  for (const ws of fs.readdirSync(dir)) {
    const wsPath = path.join(dir, ws);
    let st;
    try { st = fs.statSync(wsPath); } catch { continue; }
    if (!st.isDirectory()) continue;
    for (const sid of fs.readdirSync(wsPath)) {
      const f = path.join(wsPath, sid, 'session.v4.jsonl.zstd');
      if (fs.existsSync(f)) out.push({ file: f, ws, sid });
    }
  }
  return out;
}

// 会话文件是多帧 zstd（每次 append 一帧），Node 的 zstdDecompressSync 只解第一帧。
// 这里按 frame magic 切分逐帧解压；遇到假 magic（解压失败）就扩到下一帧再试。
function decompressMultiFrame(buf) {
  const M = [0x28, 0xb5, 0x2f, 0xfd];
  const pos = [];
  for (let i = 0; i < buf.length - 3; i++) {
    if (buf[i] === M[0] && buf[i + 1] === M[1] && buf[i + 2] === M[2] && buf[i + 3] === M[3]) pos.push(i);
  }
  if (!pos.length) return Buffer.alloc(0);
  const chunks = [];
  let start = pos[0];
  let k = 1;
  while (start < buf.length && k <= pos.length) {
    const end = k < pos.length ? pos[k] : buf.length;
    try {
      chunks.push(zlib.zstdDecompressSync(buf.subarray(start, end)));
      start = end;
      k++;
    } catch {
      k++; // 假 magic，扩到下一帧再试
    }
  }
  return Buffer.concat(chunks);
}

function readLines(file) {
  const buf = decompressMultiFrame(fs.readFileSync(file));
  return buf.toString('utf8').split('\n');
}

function existingKeys() {
  const set = new Set();
  if (!fs.existsSync(OUTDIR)) return set;
  for (const f of fs.readdirSync(OUTDIR)) {
    if (!f.endsWith('.jsonl')) continue;
    for (const line of fs.readFileSync(path.join(OUTDIR, f), 'utf8').split('\n')) {
      if (!line.trim()) continue;
      try {
        const j = JSON.parse(line);
        if (j.session != null && j.seq != null) set.add(j.session + ':' + j.seq);
      } catch { /* skip */ }
    }
  }
  return set;
}

function flagsOf(rec) {
  const f = [];
  const u = rec.user || '';
  if (/记住|以后|规则|约定|always remember|from now on/i.test(u)) f.push('explicit');
  // 不把 toolFail 塞进 flags：它已单独 +5，重复计分会把寒暄也拉高分
  return f;
}

function main() {
  fs.mkdirSync(OUTDIR, { recursive: true });
  // 单实例锁：所有调用方（手动 / service.d / schedule）共用，防并发重复写
  const LOCK = path.join(HOME, 'memory', '.collect.lock');
  try {
    fs.mkdirSync(LOCK);
  } catch {
    console.log(JSON.stringify({ skipped: 'another instance running' }));
    return;
  }
  const release = () => { try { fs.rmdirSync(LOCK); } catch {} };
  process.on('exit', release);
  process.on('SIGINT', () => { release(); process.exit(1); });

  const seen = existingKeys();
  const day = localDay();
  const outFile = path.join(OUTDIR, day + '.jsonl');

  const rows = [];
  const files = walkDirs(SESSIONS);
  let total = 0;
  let bad = 0;
  let turns = 0;

  for (const { file, ws, sid } of files) {
    let lines;
    try {
      lines = readLines(file);
    } catch (e) {
      console.error('skip file', file, e.message);
      continue;
    }
    let cur = null;

    for (const line of lines) {
      if (!line.trim()) continue;
      let ev;
      try {
        ev = JSON.parse(line);
      } catch {
        bad++;
        continue;
      }
      total++;

      if (ev.type === 'user/message') {
        // 只收真实用户输入：source.kind === 'user'
        // （agent-instructions / runtime-context / time-context 是系统注入，实测 674 条里只有 441 条是 user）
        // 注意：跳过时**不清空 cur**——fed3 这类会话的序列是 user → runtime-context → assistant，
        // 清空 cur 会把整轮丢掉（实测 3 个会话因此漏采）
        if (ev.data?.source?.kind !== 'user') continue;
        let t = textOf(ev.data?.content);

        // 直投通道：零一的消息没有 TG 前缀（「【零一 · 直投通道开通测试】…—— 零一（Hermes · CT106）」）
        // 它占的是 user 位置（turn 开头），所以清空 cur 是对的
        if (/^【零一[^】]*】/.test(t) || /—— 零一（Hermes/.test(t)) { cur = null; continue; }
        // 兜底：无中继前缀的 @零八 = 零一直投（456 的话必带中继前缀）
        if (/^@zeroeight_040916_bot/.test(t) && !/^【TG群/.test(t)) { cur = null; continue; }

        // TG 转发前缀：【TG群「456总控室」】（群内新消息转发）Starshards456 说：
        // 三个污染源（零一 2026-11-07 实测）：
        //  1) 零一的话（197+1 条）被当成 user → 丢
        //  2) 前缀（124 条）→ 剥
        //  3) 尾部指令「—— 要在群里回应…」→ 剥
        const M = /^【TG群[^】]*】（群内新消息转发）(.+?) 说：\n/;
        const m = t.match(M);
        if (m) {
          if (/机器人|零一/.test(m[1])) { cur = null; continue; } // 不是 456 说的话
          t = t.slice(m[0].length);
          t = t.replace(/\n—— 要在群里回应[\s\S]*$/, '');
        }

        // 一个 turn 里多条 user/message（插话）时，后一条覆盖前一条——故意
        if (t) cur = { user: clip(t, MAX), seq: ev.seq ?? null, userTs: ev.time ?? null };
      } else if (ev.type === 'assistant/message') {
        const t = textOf(ev.data?.message?.content);
        if (cur && t) cur.assistant = clip(t, MAX);
      } else if (ev.type === 'tool/result' && cur) {
        const failed =
          ev.data?.error !== undefined || ev.data?.message?.isError === true;
        if (failed) cur.toolFail = (cur.toolFail || 0) + 1;
      } else if (ev.type === 'turn/end' && cur) {
        turns++;
        if (cur.user || cur.assistant) {
          const key = sid + ':' + (cur.seq ?? '');
          if (!seen.has(key)) {
            seen.add(key);
            const rec = {
              ts: ev.time ?? null,        // turn 结束时间（事件时间）
              userTs: cur.userTs,         // 用户说话时间
              session: sid,
              ws,
              seq: cur.seq ?? null,
              turn: ev.data?.turn ?? null,
              reason: ev.data?.reason?.kind ?? null,
              user: cur.user || '',
              assistant: cur.assistant || '',
              toolFail: cur.toolFail || 0,
            };
            rec.flags = flagsOf(rec);
            rows.push(rec);
          }
        }
        cur = null;
      }
    }
  }

  if (rows.length) {
    fs.appendFileSync(outFile, rows.map((r) => JSON.stringify(r)).join('\n') + '\n');
  }
  console.log(
    JSON.stringify({
      day,
      sessions: files.length,
      scanned_lines: total,
      bad_lines: bad,
      turns,
      new_rows: rows.length,
      out: outFile,
    })
  );
}

main();
