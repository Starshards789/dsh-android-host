#!/usr/bin/env node
/**
 * dsh-trust —— dsh 升级的信任根（P2「能锁」）
 *
 * 为什么需要它：
 *   npm 自带 integrity（sha512）能证明「下载的 tarball 是 registry 说的那个」，
 *   但它默认信任 registry。一旦 registry 被劫持、或者镜像被投毒，那层校验就形同虚设。
 *   这里建立一条**属于我们自己的信任链**：
 *
 *     私钥（只在本机/Vaultwarden，永不进设备、永不进仓库）
 *        └─ 签 manifest.json（版本 + 官方 integrity + 关键文件哈希 + patch 记录）
 *             └─ 公钥（部署到设备）验签
 *
 * 三个子命令：
 *   keygen                    生成 Ed25519 密钥对（只在开发机跑一次）
 *   sign   <dshroot> <ver>    为某个 dshroot 目录生成并签名 manifest.json
 *   verify <dshroot>          校验 manifest 签名 + 关键文件哈希
 *
 * 设计要点（每条都有理由）：
 *   ① 只对「关键文件」做哈希，不扫全目录 —— 458M/44507 文件扫一遍太慢，
 *      而真正决定「这是什么版本」的是 package.json 与入口文件。
 *   ② patch 单独列账：Android 适配 patch 会改变文件哈希，必须把它记成
 *      「受信任的修改」而不是「篡改」。patch 的哈希也进清单。
 *   ③ 签名前对 JSON 做规范化（键排序、无空白），否则同样内容不同排版就验不过。
 *   ④ 验签失败是硬失败，不允许「警告后继续」。
 *
 * 用法示例：
 *   node dsh-trust.js keygen --out /root/dsh-trust
 *   node dsh-trust.js sign /data/user/0/cc.bestly.dsh/files/payload/dshroot 0.2.1-rc.2 \
 *        --key /root/dsh-trust/dsh-trust.key
 *   node dsh-trust.js verify /data/user/0/cc.bestly.dsh/files/payload/dshroot \
 *        --pub /data/adb/dsh-tools/dsh-trust.pub
 */

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const MANIFEST_NAME = 'dsh-manifest.json';
const SIG_NAME = 'dsh-manifest.sig';
const SCHEMA = 1;

/** 关键文件：相对 <dshroot>/lib/node_modules/ 的路径，决定「这是什么版本」 */
const KEY_FILES = [
    '@deepseek-ai/dsh/package.json',
    '@deepseek-ai/dsh/lib/bin.js',
    '@deepseek-ai/dsh/lib/profile-boot.js',
];

/** 必须存在的 Android 适配 patch（改了上游行为，要单独列账） */
const PATCH_TARGETS = [
    'node-addon-require-builtin/lib/index.js',
    '@deepseek-ai/dsh/node_modules/node-addon-require-builtin/lib/index.js',
];

// ---------------- 工具 ----------------

function die(msg) {
    console.error(`dsh-trust: ${msg}`);
    process.exit(1);
}

function sha256File(p) {
    const h = crypto.createHash('sha256');
    h.update(fs.readFileSync(p));
    return h.digest('hex');
}

/** 规范化 JSON：递归键排序、无多余空白。签名与验签必须用同一套序列化。 */
function canon(obj) {
    if (obj === null || typeof obj !== 'object') return JSON.stringify(obj);
    if (Array.isArray(obj)) return '[' + obj.map(canon).join(',') + ']';
    const keys = Object.keys(obj).sort();
    return '{' + keys.map((k) => JSON.stringify(k) + ':' + canon(obj[k])).join(',') + '}';
}

function parseArgs(argv) {
    const out = { _: [] };
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        if (a.startsWith('--')) {
            const k = a.slice(2);
            const v = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : true;
            out[k] = v;
        } else out._.push(a);
    }
    return out;
}

/** 在扁平与嵌套两种布局下找到 patch 目标的真实路径 */
function findPatchTarget(root) {
    for (const rel of PATCH_TARGETS) {
        const p = path.join(root, 'lib/node_modules', rel);
        if (fs.existsSync(p)) return { rel, abs: p };
    }
    return null;
}

/** 从 dsh 的 package.json 读版本 */
function dshVersion(root) {
    const p = path.join(root, 'lib/node_modules/@deepseek-ai/dsh/package.json');
    if (!fs.existsSync(p)) return null;
    try {
        return JSON.parse(fs.readFileSync(p, 'utf8')).version || null;
    } catch {
        return null;
    }
}

// ---------------- 子命令 ----------------

function cmdKeygen(args) {
    const outDir = args.out || '.';
    fs.mkdirSync(outDir, { recursive: true });
    const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519');
    const keyPath = path.join(outDir, 'dsh-trust.key');
    const pubPath = path.join(outDir, 'dsh-trust.pub');

    fs.writeFileSync(keyPath, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
    fs.writeFileSync(pubPath, publicKey.export({ type: 'spki', format: 'pem' }), { mode: 0o644 });

    const pubDer = publicKey.export({ type: 'spki', format: 'der' });
    const fingerprint = crypto.createHash('sha256').update(pubDer).digest('hex');

    console.log(`私钥: ${keyPath}  (600, 绝不外传、绝不进仓库、绝不进设备)`);
    console.log(`公钥: ${pubPath}  (部署到设备 /data/adb/dsh-tools/dsh-trust.pub)`);
    console.log(`公钥指纹(SHA256): ${fingerprint}`);
    console.log('');
    console.log('下一步：把公钥部署到设备，私钥存入 Vaultwarden。');
}

function cmdSign(args) {
    const root = args._[0] || die('用法: sign <dshroot> [version] --key <私钥> [--integrity <sha512-...>]');
    if (!fs.existsSync(root)) die(`目录不存在: ${root}`);
    const keyPath = args.key || die('缺少 --key <私钥路径>');
    if (!fs.existsSync(keyPath)) die(`私钥不存在: ${keyPath}`);

    const ver = args._[1] || dshVersion(root) || die('读不到 dsh 版本');

    const files = [];
    for (const rel of KEY_FILES) {
        const abs = path.join(root, 'lib/node_modules', rel);
        if (!fs.existsSync(abs)) {
            console.error(`  ! 关键文件缺失，跳过: ${rel}`);
            continue;
        }
        files.push({ path: rel, sha256: sha256File(abs) });
    }
    if (files.length === 0) die('一个关键文件都没找到，目录结构不对');

    const patches = [];
    const pt = findPatchTarget(root);
    if (pt) {
        patches.push({
            id: 'android-require-builtin-js-fallback',
            path: pt.rel,
            sha256: sha256File(pt.abs),
        });
    } else {
        console.error('  ! 未找到 Android 适配 patch 目标 —— 这个 dshroot 很可能起不来');
    }

    const manifest = {
        schema: SCHEMA,
        tool: 'dsh-trust',
        generated_at: new Date().toISOString(),
        dsh_version: ver,
        npm_integrity: args.integrity && args.integrity !== true ? args.integrity : null,
        key_files: files,
        patches,
    };

    const body = canon(manifest);
    const priv = crypto.createPrivateKey(fs.readFileSync(keyPath, 'utf8'));
    const sig = crypto.sign(null, Buffer.from(body, 'utf8'), priv);

    fs.writeFileSync(path.join(root, MANIFEST_NAME), JSON.stringify(manifest, null, 2) + '\n');
    fs.writeFileSync(path.join(root, SIG_NAME), sig.toString('base64') + '\n');

    console.log(`已签名: ${path.join(root, MANIFEST_NAME)}`);
    console.log(`  版本: ${ver}`);
    console.log(`  关键文件: ${files.length} 个`);
    console.log(`  patch 记录: ${patches.length} 条`);
    console.log(`  签名: ${sig.length} 字节 (Ed25519)`);
}

function cmdVerify(args) {
    const root = args._[0] || die('用法: verify <dshroot> --pub <公钥>');
    const pubPath = args.pub || die('缺少 --pub <公钥路径>');

    const mfPath = path.join(root, MANIFEST_NAME);
    const sigPath = path.join(root, SIG_NAME);
    if (!fs.existsSync(mfPath)) die(`清单不存在: ${mfPath}`);
    if (!fs.existsSync(sigPath)) die(`签名不存在: ${sigPath}`);
    if (!fs.existsSync(pubPath)) die(`公钥不存在: ${pubPath}`);

    let manifest;
    try {
        manifest = JSON.parse(fs.readFileSync(mfPath, 'utf8'));
    } catch (e) {
        die(`清单解析失败: ${e.message}`);
    }

    // 1) 验签
    const body = canon(manifest);
    const sig = Buffer.from(fs.readFileSync(sigPath, 'utf8').trim(), 'base64');
    const pub = crypto.createPublicKey(fs.readFileSync(pubPath, 'utf8'));
    let sigOk = false;
    try {
        sigOk = crypto.verify(null, Buffer.from(body, 'utf8'), pub, sig);
    } catch (e) {
        die(`验签异常: ${e.message}`);
    }
    if (!sigOk) die('✗ 验签失败：清单被篡改，或不是用配对私钥签的');

    // 2) 校验关键文件哈希
    const problems = [];
    for (const f of manifest.key_files || []) {
        const abs = path.join(root, 'lib/node_modules', f.path);
        if (!fs.existsSync(abs)) {
            problems.push(`缺失: ${f.path}`);
            continue;
        }
        const got = sha256File(abs);
        if (got !== f.sha256) problems.push(`被改: ${f.path}\n      期望 ${f.sha256}\n      实际 ${got}`);
    }
    for (const p of manifest.patches || []) {
        const abs = path.join(root, 'lib/node_modules', p.path);
        if (!fs.existsSync(abs)) {
            problems.push(`patch 目标缺失: ${p.path}`);
            continue;
        }
        const got = sha256File(abs);
        if (got !== p.sha256) problems.push(`patch 被改: ${p.path}\n      期望 ${p.sha256}\n      实际 ${got}`);
    }

    if (problems.length) {
        console.error('✗ 文件校验失败:');
        for (const p of problems) console.error('    ' + p);
        process.exit(1);
    }

    console.log(`✓ 信任链通过`);
    console.log(`  版本: ${manifest.dsh_version}`);
    console.log(`  签名: Ed25519 验证通过`);
    console.log(`  关键文件: ${(manifest.key_files || []).length} 个哈希一致`);
    console.log(`  patch: ${(manifest.patches || []).length} 条哈希一致`);
    console.log(`  签发时间: ${manifest.generated_at}`);
}

// ---------------- 主入口 ----------------

const args = parseArgs(process.argv.slice(2));
const cmd = args._.shift();
switch (cmd) {
    case 'keygen': cmdKeygen(args); break;
    case 'sign':   cmdSign(args);   break;
    case 'verify': cmdVerify(args); break;
    default:
        console.error('用法: dsh-trust {keygen|sign|verify} ...');
        process.exit(1);
}
