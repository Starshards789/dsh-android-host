# Android 适配补丁（payload 层）

这些补丁打在 **payload**（`assets/payload.zip` 解出来的 `dshroot/`）上，不在 App 代码里。
换 payload 版本后**必须重打**，否则引擎会在启动期崩。

`scripts/apply-android-patches.sh` 是自动重打脚本。

## 为什么 payload 要打补丁

payload 里 **0 个 `.node`、0 个 addon `.so`**（这是既定红线：命令行壳不携带任何原生二进制）。
而 dsh 主线的部分模块在**模块加载期**就无条件依赖原生 FFI，Android 上必然断链。

补丁原则：**只切断不可达的 win32 路径，不伪造功能**。
所有被替换的函数在真被调用时抛明确错误，而不是静默返回假数据。

## 1. `web-app/startup.js` — 解除 `--host 0.0.0.0` 封锁

**症状**
```
error: --host 0.0.0.0 is intentionally not supported yet for safety:
it would expose remote code execution to the network; use 127.0.0.1 instead
```

**原因**
dsh >= 1.14 的 schema 只允许 `127.0.0.1` / `0.0.0.0`（旧版允许绑具体网卡地址，
所以旧 payload 能直接绑 Tailscale IP）。而 `0.0.0.0` 又被这行单独拒掉 ——
两个值都被堵死，Android 上就没有可用的监听地址了。

**改法**：注释掉该行。

**补偿**（必须配套，见 `docs/android-port.md`）：
绑 `0.0.0.0` 后由 iptables 收口，只放行 `lo` 与 `tun1`，其余 DROP。

**文件**：`dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/lib/startup.js`
（第 40 行，随版本浮动，脚本按内容匹配）

## 2. `koffi` 入口 — 转发到 dsh 自带的 stub

**症状**
```
Error: Cannot find the native Koffi module; did you bundle it correctly?
    at wrapNative (.../koffi/src/koffi/index.js:172:11)
at async EntryGroup.create (.../cordis-plugin-loader/lib/index.js:65:4)
```

**链路**
```
dsh-sandbox-local
  └─ dsh-sandbox-windows-acl     ← 无条件静态 import
       └─ dsh-win32-process
            └─ koffi             ← 加载期就找原生模块
```

**原因**
`koffi` 是原生 FFI 绑定，payload 里只有源码、`build/` 为空。
但 dsh 自己已经给 Android 准备了占位实现：
`dsh-subprocess-local/lib/koffi-stub.cjs`。

**改法**
把 `koffi/index.js`（ESM）和 `koffi/index.cjs`（CJS）都改成转发到那个 stub。
一处改动覆盖所有调用方，不用逐个改 `dsh-win32-process` / `dsh-sandbox-windows-acl`。

## 3. `koffi-stub.cjs` — 补上 ABI size

**症状**
```
Error: STARTUPINFOW layout mismatch: koffi computed undefined, expected 104
    at .../dsh-win32-process/lib/index.js:63:38
```

**原因**
`dsh-win32-process` 在**模块顶层**做 ABI 校验：
```js
if (STARTUPINFOW.size !== 104) throw ...
if (PROCESS_INFORMATION.size !== 24) throw ...
```
原生 stub 的 `type()` 返回对象没有 `.size`，校验直接失败 —— 光做补丁 2 还不够。

**改法**
给 stub 增加结构体名 → ABI 大小的映射：

| 结构体 | size |
|---|---|
| `DSH_STARTUPINFOW` | 104 |
| `DSH_PROCESS_INFORMATION` | 24 |

只影响加载期校验；`load` / `alloc` / `decode` / `encode` 仍抛明确错误，
而这些调用只存在于 win32 路径，Android 不可达。

## 与 `node-addon-require-builtin.android.js` 的关系

那个补丁（仓库根 `docs/patches/` 下已有的文件）解决的是另一条链：
`dsh-app-boot` → `node-addon-require-builtin` 找不到原生绑定。

v1.14.0 的 payload 上这条链**没有报错**，说明新版可能已内置回退；保留该文件备用。
如果新 payload 又报 `No usable native binding found`，把它覆盖到对应模块入口即可。

## 重打流程

```bash
# 手机已 root，payload 在 /data/data/cc.bestly.dsh/files/payload
./scripts/apply-android-patches.sh [--payload <path>] [--dry-run]
```

脚本会自动备份（`.bak-<date>`）、校验、修属主与 SELinux context。
