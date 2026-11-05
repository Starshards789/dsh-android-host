# Android 适配补丁（payload 层）

这些补丁打在 **payload**（`assets/payload.zip` 解出来的 `dshroot/`）上，不在 App 代码里。
换 payload 版本后**必须重打**，否则引擎会在启动期崩，或者日志里一直刷插件加载失败。

`scripts/apply-android-patches.sh` 是自动重打脚本（幂等，可按内容识别已打过的）。

## 现状（dsh 0.2.1-rc.2 / payload 566M / 31,021 文件）

| # | 文件 | 干什么 | 不打会怎样 |
|---|---|---|---|
| 1 | `dsh-web-app/lib/startup.js` | 注释 `isWildcardHost(options.host)` 校验 | 引擎拒绝启动 |
| 2 | `dsh-host-webserver/lib/index.js` | 注释 `isWildcardAddress(parsed)) throw` | 引擎拒绝启动（0.2.1 新增的第二道） |
| 3 | `koffi/index.{cjs,js}` → `koffi-stub.cjs` | 转发到占位实现 | 加载期崩（`Cannot find the native Koffi module`） |
| 4 | `restructure/index.js` → `dist/main.cjs` | 从打包产物重新导出 | `office-to-pdf` 插件加载失败（`ERR_MODULE_NOT_FOUND: src/EncodeStream.js`） |

补丁 1、2 是**配套的**：0.2.1 把通配地址封锁从一道变成了两道，
只改 `startup.js` 会在 webserver 层再被拒一次，日志里看不出是谁拦的。

## 为什么 payload 要打补丁

payload 里 **0 个 `.node`、0 个 addon `.so`**（既定红线：壳不携带任何原生二进制）。
而 dsh 主线的部分模块在**模块加载期**就无条件依赖原生 FFI，Android 上必然断链。

补丁原则：**只切断不可达的 win32 路径，不伪造功能**。
所有被替换的函数在真被调用时抛明确错误，而不是静默返回假数据。

## 1. `web-app/startup.js` — 解除 `--host` 通配地址封锁

**症状**
```
error: --host 0.0.0.0 is an unspecified (wildcard) address, which is not supported:
binding every interface would expose remote code execution to the network;
bind one concrete IPv4 or IPv6 address instead
```

**原因**
dsh >= 1.14 的 schema 只允许 `127.0.0.1` / `0.0.0.0`（旧版允许绑具体网卡地址，
所以旧 payload 能直接绑 Tailscale IP）。而 `0.0.0.0` 又被这行单独拒掉 ——
两个值都被堵死，Android 上就没有可用的监听地址了。

**改法**：注释掉该行（0.1.5 在第 40 行，0.2.1 在第 48 行，脚本按内容匹配）。

**补偿**（必须配套）：绑 `0.0.0.0` 后由 iptables 收口，
只放行 `lo` 与 `tun1`，其余 DROP（`/data/adb/service.d/40-dsh-firewall.sh`）。

## 2. `dsh-host-webserver/lib/index.js` — 第二道封锁

**症状**：补丁 1 打完，引擎仍然起不来，`engine.log` 里是
```
webserver: host "0.0.0.0" is an unspecified (wildcard) address, which is not supported
```

**原因**：0.2.1 在 webserver 插件里又加了一道 `isWildcardAddress(parsed)` 校验（第 255 行）。
`startup.js` 那道只是 CLI 层的前置检查，真正 bind 时走的是这里。

**改法**：注释掉第 255 行的 `throw`。

## 3. `koffi` 入口 — 转发到占位 stub

**症状**
```
Error: Cannot find the native Koffi module; did you bundle it correctly?
    at wrapNative (.../koffi/src/koffi/index.js:172:11)
```

**链路**
```
dsh-sandbox-local
  └─ dsh-sandbox-windows-acl     ← 无条件静态 import
       └─ dsh-win32-process
            └─ koffi             ← 加载期就找原生模块
```

**原因**：`koffi` 是原生 FFI 绑定，payload 里只有源码、`build/` 为空。

**⚠️ 0.2.1 的变化**：`dsh-subprocess-local/lib/koffi-stub.cjs` **不存在了**，
不能再用官方自带的占位实现。现在把 `docs/patches/koffi-stub.android.cjs`
（我们自己维护的版本，带 ABI size 表）推到 `koffi/koffi-stub.cjs`，
再把 `koffi/index.cjs`（CJS）和 `koffi/index.js`（ESM）改成转发到它。

**ABI size 是必须的**（`dsh-win32-process` 在模块顶层校验）：

| 结构体 | size |
|---|---|
| `STARTUPINFOW` | 104 |
| `PROCESS_INFORMATION` | 24 |

只影响加载期校验；`load` / `alloc` / `decode` / `encode` 仍抛明确错误，
而这些调用只存在于 win32 路径，Android 不可达。

## 4. `restructure` — payload 漏打包 `src/`

**症状**
```
office-to-pdf (@deepseek-ai/dsh-office-to-pdf): failed to import:
Error [ERR_MODULE_NOT_FOUND]: Cannot find module
'.../node_modules/restructure/src/EncodeStream.js'
imported from '.../node_modules/restructure/index.js'
```

**原因**
`restructure@3.0.2` 的 `package.json` 里 `exports.import` 指向 `./index.js`（源码入口），
而 `index.js` 引用了 14 个 `./src/*.js`。官方 payload 只打包了 `dist/`，
**`src/` 整个目录不在包里** —— npm 上发的包也有同样问题（缺 src）。
`main`/`require` 走 `./dist/main.cjs` 是好的，所以只有 ESM 路径炸。

**改法**
`dist/main.cjs` 是 parcel 打好的完整实现，导出面比 `index.js` 还全（51 个 vs 17 个）。
把 `index.js` 改成从它整体重新导出（`docs/patches/restructure-index.android.js`）：

```js
import main from './dist/main.cjs';
export default main;
export const { Array, Bitfield, ..., resolveLength } = main;
```

**验证过的导出**：`EncodeStream` / `Struct` / `int32be` 等 51 个全部可用。

## 与 `node-addon-require-builtin.android.js` 的关系

那个补丁解决的是另一条链：`dsh-app-boot` → `node-addon-require-builtin` 找不到原生绑定。

0.1.5 和 0.2.1 的 payload 上这条链**都没有报错**，说明新版已内置回退；保留该文件备用。
如果新 payload 又报 `No usable native binding found`，把它覆盖到对应模块入口即可。

## 重打流程

```bash
# 手机已 root，payload 在 /data/data/cc.bestly.dsh/files/payload
./scripts/apply-android-patches.sh [--payload <path>] [--dry-run]
```

脚本行为：
- 先 `stat` 读 payload 属主，推出 SELinux category（`c<uid-10000>`），**不写死 10179/c179**
- 文本补丁走 `pull → python 改 → push`，不在 adb shell 里拼 sed
- 每个文件首次改动前留 `.bak-android-patch`
- 按 `patch-android` 标记识别已打过的，可重复执行

## 换 payload 后的检查清单

1. 解压 → `chown -R <uid>:<uid>` → `chcon -R u:object_r:app_data_file:s0:c<cat>,c256,c512,c768`
2. 跑本脚本
3. `runtime/etc/cacert.pem` 是否存在（新版 payload 不带，需要 `cat /system/etc/security/cacerts/*` 生成）
4. 启动后看 `runtime/engine.log`：应为 2 行，无 `did not activate`、无插件 `failed to import`
5. `ss -ltn | grep 13081` 确认监听
