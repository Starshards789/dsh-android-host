/**
 * dsh-tool-phone-life —— 手机生活/设备工具包
 *
 * 四个工具：
 *   phone_file      工作区文件读写（沙箱限制在 DSH_WORKSPACE 内）
 *   phone_location  当前位置（解析 dumpsys location 的 last location）
 *   phone_contacts  联系人搜索（content provider，需要 root）
 *   phone_calendar  日历读写（content provider，需要 root）
 *
 * ★ 依赖解析（同 dsh-tool-phone-health，两种布局都要成立）：
 *   - 静态 import "@deepseek-ai/dsh-tools" → ERR_MODULE_NOT_FOUND（插件在 dshhome 树）
 *   - 软链接 → root 能穿、App 被 SELinux(MCS) 挡
 *   - 复制依赖闭包 → 无限追
 *   唯一可行：top-level await + 按布局顺序探测绝对路径。
 *
 * ★ 权限策略（每条都实测过）：
 *   文件     —— App 对自己的 files/workspace 有完整读写权，不需要 root
 *   位置     —— dumpsys location 需要 root（缺 android.permission.LOCATION_HARDWARE / DUMP）
 *   联系人/日历 —— content query 需要 root（App 没申请 READ_CONTACTS / READ_CALENDAR）
 *
 * ★ 数据现状（2026-10-29 实测）：联系人 3 条有号码；日历/短信为空（用户用微信不用短信）。
 *   所以这两个工具在数据为空时返回 available:true + items:[]，而不是报错。
 *   空数据不是故障，别让模型以为工具坏了。
 */
import { existsSync, readFileSync, writeFileSync, readdirSync, statSync, mkdirSync, realpathSync } from "node:fs";
import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, resolve as resolvePath, join, relative, isAbsolute } from "node:path";

const name = "tool-phone-life";
const inject = ["tools"];

/** 定位 @deepseek-ai/dsh-tools（布局无关，见上） */
function locateDshTools() {
    const here = dirname(fileURLToPath(import.meta.url));
    const filesDir = resolvePath(here, "../../../../../../"); // → .../files
    const dshroot = resolvePath(filesDir, "payload/dshroot");
    const roots = [dshroot];
    if (process.env.DSH_ROOT) roots.push(process.env.DSH_ROOT);

    const candidates = [];
    for (const root of roots) {
        const nm = resolvePath(root, "lib/node_modules/@deepseek-ai");
        candidates.push(resolvePath(nm, "dsh-tools/lib/index.js")); // 扁平
        candidates.push(resolvePath(nm, "dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js")); // 嵌套
        candidates.push(resolvePath(nm, "dsh/lib/node_modules/@deepseek-ai/dsh-tools/lib/index.js"));
    }
    candidates.push(resolvePath(here, "../node_modules/@deepseek-ai/dsh-tools/lib/index.js"));
    for (const p of candidates) {
        try {
            if (existsSync(p)) return p;
        } catch (_) {}
    }
    return null;
}

const DSH_TOOLS_ENTRY = locateDshTools();
if (!DSH_TOOLS_ENTRY) throw new Error("[tool-phone-life] 找不到 @deepseek-ai/dsh-tools 入口");
const { defineTool } = await import(DSH_TOOLS_ENTRY);

// ---------------- 通用工具 ----------------

function run(cmd, args, timeoutMs = 6000) {
    return new Promise((res) => {
        try {
            execFile(cmd, args, { timeout: timeoutMs, maxBuffer: 4 << 20 }, (err, stdout, stderr) => {
                res({
                    ok: !err,
                    out: (stdout || "").trim(),
                    err: err ? String(stderr || err.message || "").trim().slice(0, 300) : "",
                });
            });
        } catch (e) {
            res({ ok: false, out: "", err: String(e && e.message ? e.message : e).slice(0, 300) });
        }
    });
}

let _rootCache = null;
async function hasRoot() {
    if (_rootCache !== null) return _rootCache;
    const r = await run("su", ["-c", "id -u"]);
    _rootCache = r.ok && r.out.split("\n").some((l) => l.trim() === "0");
    return _rootCache;
}

/** 以 root 跑一条 shell 命令 */
async function asRoot(cmd, timeoutMs = 8000) {
    return run("su", ["-c", cmd], timeoutMs);
}

/**
 * 解析 `content query` 的输出：
 *   Row: 0 _id=1, display_name=张三, has_phone_number=1
 * → [{ _id: "1", display_name: "张三", has_phone_number: "1" }]
 * 注意：值里可能含逗号（如 note=hello, world），所以只按 ", key=" 切分。
 */
function parseContentRows(text) {
    const rows = [];
    for (const line of text.split("\n")) {
        const m = /^Row:\s*\d+\s+(.*)$/.exec(line.trim());
        if (!m) continue;
        const body = m[1];
        const obj = {};
        // 用 lookahead 保留 "xx=, yy=" 形态
        const parts = body.split(/,\s*(?=[A-Za-z_][A-Za-z0-9_.]*=)/);
        for (const p of parts) {
            const eq = p.indexOf("=");
            if (eq <= 0) continue;
            const k = p.slice(0, eq).trim();
            const v = p.slice(eq + 1).trim();
            obj[k] = v === "NULL" ? null : v;
        }
        if (Object.keys(obj).length) rows.push(obj);
    }
    return rows;
}

/** 工作区根目录：DSH_WORKSPACE 环境变量优先，否则用 App files/workspace */
function workspaceRoot() {
    if (process.env.DSH_WORKSPACE && existsSync(process.env.DSH_WORKSPACE)) return process.env.DSH_WORKSPACE;
    const here = dirname(fileURLToPath(import.meta.url));
    const filesDir = resolvePath(here, "../../../../../../");
    const fallback = resolvePath(filesDir, "workspace");
    return fallback;
}

/** 把用户给的相对路径限制在 workspace 内，防目录穿越 */
function safeJoin(rel) {
    const root = workspaceRoot();
    const target = isAbsolute(rel) ? rel : join(root, rel);
    const normalizedRoot = realpathSync(root);
    let normalizedTarget;
    try {
        normalizedTarget = realpathSync(target);
    } catch (_) {
        // 文件还不存在（写新文件），规范化父目录再拼
        const parent = dirname(target);
        let rp;
        try {
            rp = realpathSync(parent);
        } catch (_) {
            rp = parent;
        }
        normalizedTarget = join(rp, target.slice(parent.length + 1 || target.length));
    }
    const relPath = relative(normalizedRoot, normalizedTarget);
    if (relPath.startsWith("..") || isAbsolute(relPath)) {
        return { ok: false, error: `路径越界：只允许访问工作区 ${normalizedRoot} 内的文件` };
    }
    return { ok: true, path: normalizedTarget, root: normalizedRoot };
}

// ---------------- 工具 1：文件 ----------------

function makeFileTool() {
    return defineTool({
        name: "phone_file",
        description:
            "在手机上的工作区里读写文件。工作区是 AI 自己的沙箱目录（App 私有 files/workspace），不会碰到系统或其他 App 的文件。" +
            "支持：list 列目录、read 读文本、write 写文本、append 追加、mkdir 建目录、stat 看信息、search 搜内容。",
        parameters: {
            action: { type: "string", description: "操作：list | read | write | append | mkdir | stat | search" },
            path: { type: "string", description: "相对工作区的路径，如 notes/todo.md（list 时可用 . 或省略）" },
            content: { type: "string", description: "write/append 时写入的文本内容" },
            pattern: { type: "string", description: "search 时要找的文本（不区分大小写）" },
            maxBytes: { type: "number", description: "read 时最多读多少字节，默认 200000" },
        },
        output: {
            schema: {
                type: "object",
                additionalProperties: false,
                properties: {
                    ok: { type: "boolean", required: true },
                    error: { type: "string" },
                    workspace: { type: "string" },
                    path: { type: "string" },
                    entries: { type: "array", items: { type: "object", additionalProperties: true } },
                    content: { type: "string" },
                    truncated: { type: "boolean" },
                    bytes: { type: "number" },
                    matches: { type: "array", items: { type: "object", additionalProperties: true } },
                    summary: { type: "string" },
                },
            },
            render: (_a, v) => {
                if (!v.ok) return [{ type: "text", text: `phone_file 失败：${v.error || "未知错误"}` }];
                if (Array.isArray(v.entries) && v.entries.length) {
                    const lines = [`工作区 ${v.path || "."}（${v.summary}）：`];
                    for (const e of v.entries.slice(0, 100)) {
                        lines.push(`- ${e.type === "dir" ? "📁" : "📄"} ${e.name}${e.size != null ? ` (${e.size}B)` : ""}`);
                    }
                    return [{ type: "text", text: lines.join("\n") }];
                }
                if (Array.isArray(v.matches) && v.matches.length) {
                    const lines = [v.summary];
                    for (const m of v.matches.slice(0, 50)) lines.push(`- ${m.path}:${m.line}  ${m.text}`);
                    return [{ type: "text", text: lines.join("\n") }];
                }
                if (typeof v.content === "string") {
                    return [{ type: "text", text: `${v.summary}\n\n${v.content.slice(0, 8000)}${v.truncated ? "\n…（已截断）" : ""}` }];
                }
                return [{ type: "text", text: v.summary || "完成" }];
            },
        },
        async execute(args) {
            const action = String(args?.action || "list").toLowerCase();
            const rel = String(args?.path || ".");
            const chk = safeJoin(rel);
            if (!chk.ok) return { ok: false, error: chk.error, workspace: workspaceRoot() };
            const full = chk.path;

            try {
                if (action === "list") {
                    if (!existsSync(full)) return { ok: false, error: `目录不存在：${rel}`, workspace: chk.root };
                    const names = readdirSync(full);
                    const entries = names.slice(0, 500).map((n) => {
                        const p = join(full, n);
                        let st;
                        try {
                            st = statSync(p);
                        } catch (_) {}
                        return {
                            name: n,
                            type: st?.isDirectory() ? "dir" : "file",
                            size: st?.isFile() ? st.size : undefined,
                            mtime: st ? new Date(st.mtimeMs).toISOString() : undefined,
                        };
                    });
                    return {
                        ok: true,
                        workspace: chk.root,
                        path: rel,
                        entries,
                        summary: `${rel} 下 ${entries.length} 项（${entries.filter((e) => e.type === "dir").length} 目录 / ${entries.filter((e) => e.type === "file").length} 文件）`,
                    };
                }

                if (action === "read") {
                    if (!existsSync(full)) return { ok: false, error: `文件不存在：${rel}`, workspace: chk.root };
                    const st = statSync(full);
                    if (st.isDirectory()) return { ok: false, error: `${rel} 是目录，用 action=list` };
                    const cap = Math.min(Number(args?.maxBytes) || 200000, 2_000_000);
                    const buf = readFileSync(full);
                    const truncated = buf.length > cap;
                    const text = buf.slice(0, cap).toString("utf8");
                    return {
                        ok: true,
                        workspace: chk.root,
                        path: rel,
                        content: text,
                        truncated,
                        bytes: st.size,
                        summary: `读取 ${rel}：${st.size} 字节${truncated ? `（已截断到 ${cap}）` : ""}`,
                    };
                }

                if (action === "write" || action === "append") {
                    const text = String(args?.content ?? "");
                    mkdirSync(dirname(full), { recursive: true });
                    if (action === "append" && existsSync(full)) {
                        writeFileSync(full, text, { flag: "a" });
                    } else {
                        writeFileSync(full, text);
                    }
                    const st = statSync(full);
                    return {
                        ok: true,
                        workspace: chk.root,
                        path: rel,
                        bytes: st.size,
                        summary: `${action === "append" ? "追加" : "写入"} ${rel}：现在 ${st.size} 字节`,
                    };
                }

                if (action === "mkdir") {
                    mkdirSync(full, { recursive: true });
                    return { ok: true, workspace: chk.root, path: rel, summary: `目录已就绪：${rel}` };
                }

                if (action === "stat") {
                    if (!existsSync(full)) return { ok: false, error: `不存在：${rel}`, workspace: chk.root };
                    const st = statSync(full);
                    return {
                        ok: true,
                        workspace: chk.root,
                        path: rel,
                        bytes: st.size,
                        summary: `${rel}：${st.isDirectory() ? "目录" : "文件"}，${st.size} 字节，修改于 ${new Date(st.mtimeMs).toISOString()}`,
                    };
                }

                if (action === "search") {
                    const pat = String(args?.pattern || "");
                    if (!pat) return { ok: false, error: "search 需要 pattern", workspace: chk.root };
                    const needle = pat.toLowerCase();
                    const matches = [];
                    const walk = (dir, depth) => {
                        if (depth > 6 || matches.length >= 100) return;
                        let ns = [];
                        try {
                            ns = readdirSync(dir);
                        } catch (_) {
                            return;
                        }
                        for (const n of ns) {
                            if (matches.length >= 100) return;
                            const p = join(dir, n);
                            let st;
                            try {
                                st = statSync(p);
                            } catch (_) {
                                continue;
                            }
                            if (st.isDirectory()) {
                                walk(p, depth + 1);
                            } else if (st.size < 1_000_000) {
                                try {
                                    const lines = readFileSync(p, "utf8").split("\n");
                                    lines.forEach((ln, i) => {
                                        if (matches.length < 100 && ln.toLowerCase().includes(needle)) {
                                            matches.push({
                                                path: relative(chk.root, p),
                                                line: i + 1,
                                                text: ln.trim().slice(0, 200),
                                            });
                                        }
                                    });
                                } catch (_) {}
                            }
                        }
                    };
                    walk(full, 0);
                    return {
                        ok: true,
                        workspace: chk.root,
                        path: rel,
                        matches,
                        summary: `在 ${rel} 下找到 ${matches.length} 处匹配「${pat}」`,
                    };
                }

                return { ok: false, error: `未知 action：${action}`, workspace: chk.root };
            } catch (e) {
                return {
                    ok: false,
                    error: String(e && e.message ? e.message : e).slice(0, 300),
                    workspace: chk.root,
                    path: rel,
                };
            }
        },
    });
}

// ---------------- 工具 2：位置 ----------------

function makeLocationTool() {
    return defineTool({
        name: "phone_location",
        description:
            "查询手机当前/最近的位置（经纬度、精度、定位来源）与系统时区时间。" +
            "位置来自系统 LocationManager 的 last known location；需要 root（App 没有定位权限）。" +
            "只返回坐标，不做逆地理编码——需要地址就让上层再处理。",
        parameters: {},
        output: {
            schema: {
                type: "object",
                additionalProperties: false,
                properties: {
                    ok: { type: "boolean", required: true },
                    error: { type: "string" },
                    available: { type: "boolean" },
                    provider: { type: "string" },
                    latitude: { type: "number" },
                    longitude: { type: "number" },
                    accuracyM: { type: "number" },
                    ageSeconds: { type: "number" },
                    locationEnabled: { type: "boolean" },
                    timezone: { type: "string" },
                    localTime: { type: "string" },
                    epochSeconds: { type: "number" },
                    summary: { type: "string" },
                },
            },
            render: (_a, v) => {
                if (!v.ok) return [{ type: "text", text: `phone_location 失败：${v.error || "未知错误"}` }];
                if (!v.available) {
                    return [{ type: "text", text: `位置不可用：${v.error || "无数据"}\n${v.summary || ""}` }];
                }
                const lines = [
                    `当前位置：${v.latitude}, ${v.longitude}`,
                    `精度：±${v.accuracyM}m（来源 ${v.provider}）`,
                    v.locationEnabled === false ? "⚠ 系统定位开关是关的（这是历史缓存位置）" : "",
                    `本地时间：${v.localTime} ${v.timezone}`,
                    "",
                    "提示：这里只有坐标，没有地址。需要地址就再查一次（例如用逆地理编码服务）。",
                ].filter(Boolean);
                return [{ type: "text", text: lines.join("\n") }];
            },
        },
        async execute() {
            const tz = await asRoot("getprop persist.sys.timezone");
            const now = await asRoot("date +%s; date");
            const [epoch, human] = now.out.split("\n");

            if (!(await hasRoot())) {
                return {
                    ok: true,
                    available: false,
                    error: "需要 root：App 没有定位权限，读不了 LocationManager 的 last location",
                    timezone: tz.out || null,
                    localTime: human || null,
                    epochSeconds: Number(epoch) || null,
                    summary: `位置不可用（需 root）；当前 ${human || "?"} ${tz.out || ""}`,
                };
            }

            const dump = await asRoot("dumpsys location 2>/dev/null | head -120");
            // last location=Location[network 22.6412,114.0335 hAcc=22.5 et=+6h38m...]
            const m = /last location=Location\[(\w+)\s+(-?\d+\.\d+),(-?\d+\.\d+)\s+hAcc=([\d.]+)/.exec(dump.out);
            const enabled = /enabled=(true|false)/.exec(dump.out);

            if (!m) {
                return {
                    ok: true,
                    available: false,
                    error: "没拿到 last location（系统可能刚清空，或从未定位过）",
                    locationEnabled: enabled ? enabled[1] === "true" : null,
                    timezone: tz.out || null,
                    localTime: human || null,
                    epochSeconds: Number(epoch) || null,
                    summary: `位置为空；定位开关=${enabled ? enabled[1] : "未知"}；当前 ${human || "?"}`,
                };
            }

            const lat = Number(m[2]);
            const lon = Number(m[3]);
            const acc = Number(m[4]);
            const provider = m[1];
            return {
                ok: true,
                available: true,
                provider,
                latitude: lat,
                longitude: lon,
                accuracyM: acc,
                locationEnabled: enabled ? enabled[1] === "true" : null,
                timezone: tz.out || null,
                localTime: human || null,
                epochSeconds: Number(epoch) || null,
                summary: `位置 ${lat.toFixed(4)},${lon.toFixed(4)}（${provider}，精度 ±${acc}m）；当前 ${human || "?"} ${tz.out || ""}`,
            };
        },
    });
}

// ---------------- 工具 3：联系人 ----------------

function makeContactsTool() {
    return defineTool({
        name: "phone_contacts",
        description:
            "搜索/列出手机联系人（姓名 + 电话号码）。需要 root（App 没申请 READ_CONTACTS）。" +
            "query 留空则列出全部（最多 limit 条）。用于「某某的号码是多少」「我有几个联系人存了」这类问题。",
        parameters: {
            query: { type: "string", description: "姓名关键词，不区分大小写；留空=全部" },
            limit: { type: "number", description: "最多返回条数，默认 30" },
        },
        output: {
            schema: {
                type: "object",
                additionalProperties: false,
                properties: {
                    ok: { type: "boolean", required: true },
                    error: { type: "string" },
                    available: { type: "boolean" },
                    total: { type: "number" },
                    contacts: { type: "array", items: { type: "object", additionalProperties: true } },
                    summary: { type: "string" },
                },
            },
            render: (_a, v) => {
                if (!v.ok) return [{ type: "text", text: `phone_contacts 失败：${v.error || "未知错误"}` }];
                if (!v.available) return [{ type: "text", text: `联系人不可用：${v.error || ""}` }];
                const lines = [v.summary];
                for (const c of (v.contacts || [])) lines.push(`- ${c.name}：${(c.phones || []).join(" / ")}`);
                return [{ type: "text", text: lines.join("\n") }];
            },
        },
        async execute(args) {
            if (!(await hasRoot())) {
                return {
                    ok: true,
                    available: false,
                    error: "需要 root：App 没申请 READ_CONTACTS 权限",
                    summary: "联系人不可用（需 root）",
                };
            }
            const q = String(args?.query || "").trim();
            const limit = Math.min(Number(args?.limit) || 30, 200);

            // 用 data 表能一次拿到姓名+号码
            const where = q ? `--where "display_name LIKE '%${q.replace(/'/g, "")}%'"` : "";
            const r = await asRoot(
                `content query --uri content://com.android.contacts/data --projection display_name:data1:mimetype ${where} 2>&1 | head -800`
            );
            if (!r.ok && !r.out) {
                return {
                    ok: true,
                    available: false,
                    error: r.err || "content query 失败",
                    summary: "读联系人失败",
                };
            }
            const rows = parseContentRows(r.out);
            const byName = new Map();
            for (const row of rows) {
                const nm = row.display_name;
                const num = row.data1;
                const mt = row.mimetype || "";
                if (!nm) continue;
                if (!/phone/.test(mt)) continue;
                if (!byName.has(nm)) byName.set(nm, []);
                if (num) byName.get(nm).push(num);
            }
            const contacts = [...byName.entries()]
                .slice(0, limit)
                .map(([n, phones]) => ({ name: n, phones }));
            return {
                ok: true,
                available: true,
                total: byName.size,
                contacts,
                summary: contacts.length
                    ? `${byName.size} 个有号码的联系人${q ? `（筛选「${q}」）` : ""}，返回前 ${contacts.length} 个`
                    : `没有匹配「${q}」的联系人`,
            };
        },
    });
}

// ---------------- 工具 4：日历 ----------------

function makeCalendarTool() {
    return defineTool({
        name: "phone_calendar",
        description:
            "读写手机日历事件。需要 root（App 没申请 READ_CALENDAR / WRITE_CALENDAR）。" +
            "action=list 列出事件（可用 days 指定往后几天，默认 7）；action=create 新建事件（需要 title + start，ISO8601 或 YYYY-MM-DD HH:mm）。" +
            "注意：这台手机的自带日历可能是空的（用户不用），空结果不代表工具坏。",
        parameters: {
            action: { type: "string", description: "list | create" },
            days: { type: "number", description: "list 时往后看几天，默认 7" },
            title: { type: "string", description: "create 时的事件标题" },
            start: { type: "string", description: "create 时的开始时间，如 2026-10-30 09:00" },
            end: { type: "string", description: "create 时的结束时间（可选，默认开始后 1 小时）" },
            description: { type: "string", description: "create 时的事件备注（可选）" },
            calendarId: { type: "number", description: "create 时的日历 id，默认用主日历" },
            limit: { type: "number", description: "list 最多返回条数，默认 50" },
        },
        output: {
            schema: {
                type: "object",
                additionalProperties: false,
                properties: {
                    ok: { type: "boolean", required: true },
                    error: { type: "string" },
                    available: { type: "boolean" },
                    events: { type: "array", items: { type: "object", additionalProperties: true } },
                    created: { type: "object", additionalProperties: true },
                    summary: { type: "string" },
                },
            },
            render: (_a, v) => {
                if (!v.ok) return [{ type: "text", text: `phone_calendar 失败：${v.error || "未知错误"}` }];
                if (!v.available) return [{ type: "text", text: `日历不可用：${v.error || ""}` }];
                if (v.created) {
                    return [{ type: "text", text: `已创建：${v.created.title}\n开始 ${v.created.start}\n结束 ${v.created.end}` }];
                }
                const lines = [v.summary];
                for (const e of (v.events || [])) {
                    lines.push(`- ${e.start} → ${e.end}  ${e.title}${e.allDay ? "（全天）" : ""}${e.location ? ` @${e.location}` : ""}`);
                }
                return [{ type: "text", text: lines.join("\n") }];
            },
        },
        async execute(args) {
            if (!(await hasRoot())) {
                return {
                    ok: true,
                    available: false,
                    error: "需要 root：App 没申请日历权限",
                    summary: "日历不可用（需 root）",
                };
            }
            const action = String(args?.action || "list").toLowerCase();

            if (action === "list") {
                const days = Math.min(Number(args?.days) || 7, 365);
                const limit = Math.min(Number(args?.limit) || 50, 200);
                const nowSec = Math.floor(Date.now() / 1000);
                const until = nowSec + days * 86400;
                const r = await asRoot(
                    `content query --uri content://com.android.calendar/events ` +
                        `--projection _id:calendar_id:title:dtstart:dtend:allDay:eventLocation:description ` +
                        `--where "dtend>=${nowSec} AND dtstart<=${until} AND deleted=0" 2>&1 | head -400`
                );
                const rows = parseContentRows(r.out);
                const events = rows
                    .map((row) => {
                        const s = Number(row.dtstart);
                        const e = Number(row.dtend);
                        return {
                            id: row._id,
                            title: row.title,
                            start: Number.isFinite(s) ? new Date(s).toISOString() : null,
                            end: Number.isFinite(e) ? new Date(e).toISOString() : null,
                            allDay: row.allDay === "1",
                            location: row.eventLocation || null,
                        };
                    })
                    .filter((e) => e.start)
                    .sort((a, b) => (a.start < b.start ? -1 : 1))
                    .slice(0, limit);
                return {
                    ok: true,
                    available: true,
                    events,
                    summary: events.length
                        ? `未来 ${days} 天有 ${events.length} 个日程，最近：${events[0].title} @ ${events[0].start}`
                        : `未来 ${days} 天没有日程（日历可能为空，不代表工具异常）`,
                };
            }

            if (action === "create") {
                const title = String(args?.title || "").trim();
                const startStr = String(args?.start || "").trim();
                if (!title || !startStr) {
                    return { ok: false, error: "create 需要 title 和 start" };
                }
                const parse = (s) => {
                    // 支持 "YYYY-MM-DD HH:mm" 或 ISO
                    const t = s.includes("T") ? Date.parse(s) : Date.parse(s.replace(" ", "T") + ":00+08:00");
                    return Number.isFinite(t) ? t : NaN;
                };
                const startMs = parse(startStr);
                if (!Number.isFinite(startMs)) {
                    return { ok: false, error: `时间解析失败：${startStr}（示例：2026-10-30 09:00）` };
                }
                const endMs = args?.end ? parse(String(args.end)) : startMs + 3600_000;
                if (!Number.isFinite(endMs) || endMs <= startMs) {
                    return { ok: false, error: "结束时间无效（必须晚于开始时间）" };
                }
                let calId = Number(args?.calendarId);
                if (!Number.isFinite(calId)) {
                    const cr = await asRoot(
                        `content query --uri content://com.android.calendar/calendars --projection _id:calendar_displayName:isPrimary 2>&1 | head -20`
                    );
                    const cals = parseContentRows(cr.out);
                    const primary = cals.find((c) => c.isPrimary === "1") || cals[0];
                    calId = primary ? Number(primary._id) : NaN;
                }
                if (!Number.isFinite(calId)) {
                    return { ok: false, error: "找不到可用日历（设备上没有可写日历账户）" };
                }
                const desc = String(args?.description || "")
                    .replace(/["'\\]/g, "")
                    .slice(0, 500);
                const tzEsc = "Asia/Shanghai";
                const cmd =
                    `content insert --uri content://com.android.calendar/events ` +
                    `--bind calendar_id:i:${calId} --bind title:s:"${title.replace(/["\\]/g, "")}" ` +
                    `--bind dtstart:l:${startMs} --bind dtend:l:${endMs} ` +
                    `--bind eventTimezone:s:${tzEsc} --bind description:s:"${desc}"`;
                const ins = await asRoot(cmd);
                if (!ins.ok && /error|exception/i.test(ins.err + ins.out)) {
                    return { ok: false, error: ins.err || ins.out };
                }
                return {
                    ok: true,
                    available: true,
                    created: {
                        title,
                        start: new Date(startMs).toISOString(),
                        end: new Date(endMs).toISOString(),
                        calendarId: calId,
                    },
                    summary: `已创建日程「${title}」，${new Date(startMs).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" })} 开始`,
                };
            }

            return { ok: false, error: `未知 action：${action}` };
        },
    });
}

// ---------------- 插件入口 ----------------

function apply(ctx) {
    ctx.tools.register(makeFileTool());
    ctx.tools.register(makeLocationTool());
    ctx.tools.register(makeContactsTool());
    ctx.tools.register(makeCalendarTool());
}

export { apply, inject, name };
