/**
 * dsh-tool-phone-health —— 手机健康状态查询插件
 *
 * ★ 依赖解析（这是踩了很久的坑，务必保留这段说明）
 *
 * 本插件装在 dshhome/profiles/web/node_modules/ 下（保证升级 dsh 内核不被冲掉），
 * 而 @deepseek-ai/dsh-tools 在 dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/ 下。
 * 两棵树之间：
 *   - 静态 `import ... from "@deepseek-ai/dsh-tools"` → ERR_MODULE_NOT_FOUND（ESM 只向上找 node_modules）
 *   - 软链接 @deepseek-ai → dshroot 树 → root 身份能穿，**App 身份（untrusted_app_27 + MCS category）
 *     被 SELinux 挡住**（引擎启动即 EACCES realpath）
 *   - 复制依赖闭包 → 无限追（dsh-tools → 3 个 → cordis → 2 个 → 11 个 peerDependencies…）
 *
 * 结论：用 **top-level await + 绝对路径动态 import**。App 身份直读 dshroot 路径是允许的（已实测
 * READ_OK / REALPATH_OK），只是不能穿过 dshhome→dshroot 的软链接。
 * 路径由 import.meta.url 相对推导，所以 dshroot 换版本、切 Bak 目录都不影响。
 *
 * 设计原则（每条都有实测依据）：
 *
 * ① 能直读的不走 root。App（untrusted_app）身份实测可读：
 *      /sys/class/thermal/thermal_zone*[/type|/temp]  → 温度
 *      /proc/meminfo                                   → 内存
 *      statfs('/data')                                 → 存储
 *   而这两个必须 root：
 *      /sys/class/power_supply/battery/*  → Permission denied
 *      dumpsys activity activities        → 缺 android.permission.DUMP
 *
 * ② 子项独立降级。某项拿不到就返回 { available: false, reason }，
 *   绝不因为电池读不到就让整个工具失败 —— AI 最需要的是「知道什么拿不到、为什么」。
 *
 * ③ 不假设 su 一定可用。有个 hasRoot 探测，拿不到就以 unprivileged 模式工作。
 *
 * ④ 温度做聚合而不是丢一堆 zone 给模型。118 个 thermal zone 里绝大多数是
 *   cpu-N-N-usr，逐条返回会把上下文灌满。这里只给：最高 CPU 温度、电池温度、
 *   外壳温度、以及最热 zone。
 */
import { readFileSync, readdirSync, existsSync, statfsSync } from "node:fs";
import { execFile } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, resolve as resolvePath } from "node:path";

const name = "tool-phone-health";
const inject = ["tools"];

/**
 * 定位 @deepseek-ai/dsh-tools 的入口文件。
 *
 * 从本文件 URL 出发：.../files/dshhome/profiles/web/node_modules/dsh-tool-phone-health/lib/index.js
 * 目标是：          .../files/payload/dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js
 * 所以往上 6 层到 files/，再下 payload/。同时兼容 DSH_ROOT 环境变量覆盖。
 */
function locateDshTools() {
    const here = dirname(fileURLToPath(import.meta.url)); // .../dsh-tool-phone-health/lib
    const candidates = [];

    // ① 标准布局：<files>/payload/dshroot/...
    const filesDir = resolvePath(here, "../../../../../../"); // → .../files
    candidates.push(
        resolvePath(
            filesDir,
            "payload/dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js"
        )
    );

    // ② 环境变量兜底（App 传了 DSH_ROOT 的话）
    if (process.env.DSH_ROOT) {
        candidates.push(
            resolvePath(
                process.env.DSH_ROOT,
                "lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tools/lib/index.js"
            )
        );
    }

    // ③ 直接命中 node_modules（万一以后布局变成同级）
    candidates.push(resolvePath(here, "../node_modules/@deepseek-ai/dsh-tools/lib/index.js"));

    for (const p of candidates) {
        try {
            if (existsSync(p)) return p;
        } catch (_) {
            /* 继续试下一个 */
        }
    }
    return null;
}

const DSH_TOOLS_ENTRY = locateDshTools();
if (!DSH_TOOLS_ENTRY) {
    throw new Error(
        "[tool-phone-health] 找不到 @deepseek-ai/dsh-tools 入口。已尝试标准 payload 布局与 DSH_ROOT，均不存在。"
    );
}

// top-level await：模块加载期完成导入，apply 保持同步（与其他内置插件一致）
const { defineTool } = await import(DSH_TOOLS_ENTRY);

const THERMAL_ROOT = "/sys/class/thermal";
const POWER_ROOT = "/sys/class/power_supply";

/** 跑一条命令，超时 4 秒，永不抛异常 —— 返回 { ok, out, err } */
function run(cmd, args, timeoutMs = 4000) {
    return new Promise((resolve) => {
        try {
            execFile(cmd, args, { timeout: timeoutMs, maxBuffer: 1 << 20 }, (error, stdout, stderr) => {
                resolve({
                    ok: !error,
                    out: (stdout || "").trim(),
                    err: error ? (stderr || error.message || "").toString().trim().slice(0, 300) : "",
                });
            });
        } catch (e) {
            resolve({ ok: false, out: "", err: String(e && e.message ? e.message : e).slice(0, 300) });
        }
    });
}

/** su 是否可用（探测一次，结果缓存） */
let _rootCache = null;
async function hasRoot() {
    if (_rootCache !== null) return _rootCache;
    const r = await run("su", ["-c", "id -u"]);
    _rootCache = r.ok && r.out.split("\n").some((l) => l.trim() === "0");
    return _rootCache;
}

/** 需要 root 的读取：先 su -c cat，失败返回 null */
async function catAsRoot(path) {
    const r = await run("su", ["-c", `cat ${path} 2>/dev/null`]);
    if (r.ok && r.out) return r.out.split("\n")[0].trim();
    return null;
}

// ---------------- 各子项采集 ----------------

/** 电池：必须 root（App 身份读 /sys/class/power_supply/battery/* 是 Permission denied） */
async function collectBattery() {
    const files = {
        capacity: "capacity",        // %
        status: "status",            // Charging / Discharging / Full
        temp: "temp",                // 0.1℃ 单位
        current_now: "current_now",  // µA（标准）或 mA（部分 OEM），下方自适应
        voltage_now: "voltage_now",  // µV
        charge_counter: "charge_counter", // µAh
        health: "health",
        cycle_count: "cycle_count",  // 充电循环次数（部分设备不提供）
    };
    const raw = {};
    let anyOk = false;
    for (const [key, fn] of Object.entries(files)) {
        const v = await catAsRoot(`${POWER_ROOT}/battery/${fn}`);
        if (v !== null) {
            raw[key] = v;
            anyOk = true;
        }
    }
    if (!anyOk) {
        return {
            available: false,
            reason: "需要 root：App 身份读取 /sys/class/power_supply/battery/* 被拒绝",
        };
    }
    const pct = raw.capacity !== undefined ? Number(raw.capacity) : null;
    const tempC = raw.temp !== undefined ? Number(raw.temp) / 10 : null;

    /**
     * 电流量纲自适应（OPPO/ColorOS 实测坑）：
     * Android 标准是 µA，但某些 OEM 节点直接给 mA。
     * 判据：与电压一起看功率。4.47V 下 |I| 若当 µA 算只有毫瓦级 → 实际单位是 mA。
     * 阈值用 10000：标准 µA 的充电电流通常是几十万到几百万µA，
     * 而 mA 表示的通常是几百到几千。落在这两者之间的极少数情况按 mA 处理（更保守）。
     */
    let curMa = null;
    if (raw.current_now !== undefined) {
        const cur = Number(raw.current_now);
        if (Number.isFinite(cur)) {
            curMa = Math.abs(cur) >= 10000 ? cur / 1000 : cur;
        }
    }
    const voltV = raw.voltage_now !== undefined ? Number(raw.voltage_now) / 1e6 : null;

    // charge_counter 单位 µAh（标准），2666000 → 2666mAh 电池容量
    const chargeUah = raw.charge_counter !== undefined ? Number(raw.charge_counter) : null;

    return {
        available: true,
        percent: Number.isFinite(pct) ? pct : null,
        status: raw.status ?? null,
        health: raw.health ?? null,
        temperatureC: Number.isFinite(tempC) ? tempC : null,
        currentMa: Number.isFinite(curMa) ? Math.round(curMa * 10) / 10 : null,
        voltageV: Number.isFinite(voltV) ? Math.round(voltV * 100) / 100 : null,
        // 功率：负数=放电，正数=充电（按 status 归一，避免 OEM 符号约定不一致）
        powerW:
            Number.isFinite(curMa) && Number.isFinite(voltV)
                ? Math.round(((Math.abs(curMa) / 1000) * voltV) * 100) / 100
                : null,
        chargeCounterUah: Number.isFinite(chargeUah) ? chargeUah : null,
        cycleCount: raw.cycle_count !== undefined ? Number(raw.cycle_count) : null,
    };
}

/** 温度：直读（实测 App 身份可读） */
function collectThermal() {
    try {
        if (!existsSync(THERMAL_ROOT)) {
            return { available: false, reason: "本机没有 /sys/class/thermal" };
        }
        const zones = readdirSync(THERMAL_ROOT)
            .filter((d) => d.startsWith("thermal_zone"))
            .map((d) => {
                const base = `${THERMAL_ROOT}/${d}`;
                let type = "";
                let temp = "";
                try {
                    type = readFileSync(`${base}/type`, "utf8").trim();
                } catch (_) {}
                try {
                    temp = readFileSync(`${base}/temp`, "utf8").trim();
                } catch (_) {}
                const t = Number(temp);
                return { zone: d, type, celsius: Number.isFinite(t) ? t / 1000 : null };
            })
            .filter((z) => z.celsius !== null);

        if (zones.length === 0) return { available: false, reason: "thermal zone 均不可读" };

        const cpus = zones.filter((z) => /^cpu-\d/.test(z.type));
        const maxCpu = cpus.length ? Math.max(...cpus.map((z) => z.celsius)) : null;
        const avgCpu = cpus.length
            ? Math.round((cpus.reduce((s, z) => s + z.celsius, 0) / cpus.length) * 10) / 10
            : null;
        const battery = zones.find((z) => z.type === "battery");
        const shell = zones.find((z) => /shell/.test(z.type));
        const hottest = zones.reduce((a, b) => (b.celsius > a.celsius ? b : a), zones[0]);

        return {
            available: true,
            zoneCount: zones.length,
            maxCpuC: maxCpu !== null ? Math.round(maxCpu * 10) / 10 : null,
            avgCpuC: avgCpu,
            batteryC: battery ? Math.round(battery.celsius * 10) / 10 : null,
            shellC: shell ? Math.round(shell.celsius * 10) / 10 : null,
            hottestZone: hottest.type || hottest.zone,
            hottestC: Math.round(hottest.celsius * 10) / 10,
        };
    } catch (e) {
        return { available: false, reason: String(e && e.message ? e.message : e).slice(0, 200) };
    }
}

/** 存储：直读 statfs */
function collectStorage() {
    try {
        const s = statfsSync("/data");
        const total = s.blocks * s.bsize;
        const free = s.bavail * s.bsize;
        const used = total - s.bfree * s.bsize;
        const gb = (n) => Math.round((n / 1024 / 1024 / 1024) * 10) / 10;
        return {
            available: true,
            totalGb: gb(total),
            usedGb: gb(used),
            freeGb: gb(free),
            usedPercent: total > 0 ? Math.round((used / total) * 1000) / 10 : null,
        };
    } catch (e) {
        return { available: false, reason: String(e && e.message ? e.message : e).slice(0, 200) };
    }
}

/** 内存：直读 /proc/meminfo */
function collectMemory() {
    try {
        const txt = readFileSync("/proc/meminfo", "utf8");
        const grab = (key) => {
            const m = new RegExp(`^${key}:\\s+(\\d+)\\s+kB`, "m").exec(txt);
            return m ? Number(m[1]) : null;
        };
        const totalKb = grab("MemTotal");
        const availKb = grab("MemAvailable");
        const freeKb = grab("MemFree");
        const swapTotalKb = grab("SwapTotal");
        const swapFreeKb = grab("SwapFree");
        const mb = (n) => (n === null ? null : Math.round((n / 1024) * 10) / 10);
        return {
            available: true,
            totalMb: mb(totalKb),
            availableMb: mb(availKb),
            freeMb: mb(freeKb),
            usedPercent:
                totalKb && availKb !== null
                    ? Math.round(((totalKb - availKb) / totalKb) * 1000) / 10
                    : null,
            swapTotalMb: mb(swapTotalKb),
            swapFreeMb: mb(swapFreeKb),
        };
    } catch (e) {
        return { available: false, reason: String(e && e.message ? e.message : e).slice(0, 200) };
    }
}

/** 前台应用：需要 root（App 身份缺 android.permission.DUMP） */
async function collectForeground() {
    const r = await run("su", ["-c", "dumpsys activity activities 2>/dev/null | grep -m2 -E 'topResumedActivity|mResumedActivity'"]);
    if (!r.ok || !r.out) {
        return {
            available: false,
            reason: "需要 root：App 身份没有 android.permission.DUMP，读不了 ActivityManager",
        };
    }
    const m = /u0 ([a-zA-Z0-9._]+)\/([a-zA-Z0-9.$]+)/.exec(r.out);
    if (!m) {
        return { available: false, reason: "解析不到前台 Activity（dumpsys 输出格式变化）", raw: r.out.slice(0, 200) };
    }
    return { available: true, package: m[1], activity: m[2], raw: r.out.split("\n")[0].trim().slice(0, 200) };
}

// ---------------- 工具定义 ----------------

function apply(ctx) {
    ctx.tools.register(
        defineTool({
            name: "phone_health",
            description:
                "查询这台手机的健康状态快照：电量/充电状态、温度（CPU 最高与平均、电池、外壳）、存储占用、内存占用、当前前台应用。" +
                "温度/存储/内存不需要 root；电量与前台应用需要 root（拿不到会如实返回 available:false + 原因，不会静默给假数据）。" +
                "适合回答「手机烫不烫」「空间够不够」「现在前台是什么 App」「电量怎么样」这类问题。",
            parameters: {
                include: {
                    type: "string",
                    description:
                        "可选，逗号分隔的采集项，可选值：battery,temp,storage,memory,foreground。不填=全部采集。",
                },
            },
            output: {
                schema: {
                    type: "object",
                    additionalProperties: false,
                    properties: {
                        ok: { type: "boolean", required: true },
                        rootAvailable: { type: "boolean" },
                        collected: { type: "array", items: { type: "string" } },
                        battery: { type: "object", additionalProperties: true },
                        thermal: { type: "object", additionalProperties: true },
                        storage: { type: "object", additionalProperties: true },
                        memory: { type: "object", additionalProperties: true },
                        foreground: { type: "object", additionalProperties: true },
                        summary: { type: "string" },
                    },
                },
            },
            async execute(args) {
                const want = new Set(
                    String(args && args.include ? args.include : "battery,temp,storage,memory,foreground")
                        .split(",")
                        .map((s) => s.trim().toLowerCase())
                        .filter(Boolean)
                );

                const out = { ok: true, rootAvailable: await hasRoot(), collected: [] };
                const parts = [];

                if (want.has("battery")) {
                    out.battery = await collectBattery();
                    out.collected.push("battery");
                    if (out.battery.available) {
                        let b = `电量 ${out.battery.percent ?? "?"}%${out.battery.status ? `(${out.battery.status})` : ""}`;
                        if (out.battery.powerW !== null && out.battery.powerW > 0) {
                            const dir =
                                out.battery.status === "Charging"
                                    ? "充入"
                                    : out.battery.status === "Discharging"
                                      ? "放出"
                                      : "功率";
                            b += `，${dir} ${out.battery.powerW}W`;
                        }
                        parts.push(b);
                    }
                }
                if (want.has("temp")) {
                    out.thermal = collectThermal();
                    out.collected.push("thermal");
                    if (out.thermal.available) {
                        const bits = [];
                        if (out.thermal.maxCpuC !== null) bits.push(`CPU 最高 ${out.thermal.maxCpuC}℃`);
                        if (out.thermal.batteryC !== null) bits.push(`电池 ${out.thermal.batteryC}℃`);
                        if (out.thermal.shellC !== null) bits.push(`外壳 ${out.thermal.shellC}℃`);
                        if (bits.length) parts.push(bits.join(" / "));
                    }
                }
                if (want.has("storage")) {
                    out.storage = collectStorage();
                    out.collected.push("storage");
                    if (out.storage.available) {
                        parts.push(
                            `存储 剩 ${out.storage.freeGb}G / 共 ${out.storage.totalGb}G（已用 ${out.storage.usedPercent}%）`
                        );
                    }
                }
                if (want.has("memory")) {
                    out.memory = collectMemory();
                    out.collected.push("memory");
                    if (out.memory.available) {
                        parts.push(`内存 ${out.memory.usedPercent}% 已用（可用 ${out.memory.availableMb}M）`);
                    }
                }
                if (want.has("foreground")) {
                    out.foreground = await collectForeground();
                    out.collected.push("foreground");
                    if (out.foreground.available) parts.push(`前台 ${out.foreground.package}`);
                }

                out.summary = parts.length ? parts.join("；") : "没有采集到任何可用数据";
                return out;
            },
        })
    );
}

export { apply, inject, name };
