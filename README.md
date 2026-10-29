# dsh-android-host

Android 宿主 App for [dsh](https://github.com/deepseek-ai/dsh) —— 给自建 AI 系统一个自己的手机身体。

**Kotlin + Jetpack Compose** · 包名 `cc.bestly.dsh` · minSdk 24 / targetSdk 28

---

## 现在是什么状态

**P1.0 / P1.1 / P1.2 / P3 全部完成并上机实测。P1.3 代码完成，但被系统 WebView 版本卡住。**

```
App 启动 → LaunchedEffect → EngineService(前台服务)
  → EngineLauncher.start()
    → probePorts() 选一个真能 bind 的端口
    → ProcessBuilder(payload/runtime/bin/node
                     --expose-internals dsh/lib/bin.js web ...)
    → 显式构造 6 个环境变量，并剥离子进程里所有密钥类变量
    → 从 stdout 抓 http://127.0.0.1:<port>/?token=<token>
    → 写 files/runtime/{port, token, pid}
```

**App 用自己的 uid 跑引擎，不需要 root。** 和社区壳的做法同构，但引擎内核一行没改。

### 实测（Android 14 / arm64）

| 项 | 值 |
|---|---|
| 引擎进程 | `node --expose-internals .../dsh/lib/bin.js web --host 127.0.0.1 --no-open --port <p>` |
| 内存 | ≈ 330 MB |
| 端口 | 写入 `runtime/port`，LISTEN 确认 |
| 鉴权 | 无 token → `401`，带 token → `303` |
| 启动到可读状态文件 | < 10 秒 |
| 与旧壳并存 | 新宿主 13081 / 旧壳 3080，互不干扰 |

---

## 保活（P3，已完成）

ColorOS 会用 **cgroup freezer** 冻结整个 uid，前台服务不足以对抗。

| 层 | 手段 | 效果 |
|---|---|---|
| 1 | `EngineService` 前台服务 | 进程不参与常规回收 |
| 2 | `BootReceiver`（`BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`） | 开机/升级后自动拉起 |
| 3 | root 守护 `host-guard`（120 秒兜底） | 检测 `cgroup.freeze=1` → 解冻 + `am start` |
| 4 | **ColorOS 三开关**（允许完全后台行为 / 允许应用启动 / 允许应用关联启动） | **根治**：息屏 5 分钟 10 次采样 `freeze=0` / `curl=401` |

**关键实证**：解冻后必须 `am start` 拉前台，否则 I/O 不活（只 `echo 0` 仍 `curl=000`）。
`deviceidle whitelist` 与 ColorOS `no_frozen` 名单**均无效**。

---

## ⚠️ 已知限制：内置 WebView 需要 Chrome 119+

P1.3 已在 App 内做了 WebView 对话页（`usesCleartextTraffic` + token 注入 + 全屏），实测：

- ✅ dsh UI 能加载（预览说明、新建会话、插件、自动化任务、全局面板都渲染）
- ❌ 前端抛 `Promise.withResolvers is not a function` → `[session-controller] control stream failed` → 输入框与发送按钮不渲染

**根因不是宿主代码**：本项目设备系统 WebView 为 `com.google.android.webview 117.0.5938.60`（2024-01），而 dsh 前端用了 Chrome 119+ 才有的 ES2024 API。

第三方与 Play 均无法更新它，原因见下表：

| 渠道 | 结果 |
|---|---|
| APKPure / Uptodown / APKMirror | 反爬 403 / Cloudflare 挑战页 |
| Aptoide | 只有 x86（151MB）与 32 位（94MB），**无 arm64** |
| Play 商店 | 下载成功，**安装被拒**（`status_code=6581`，`installerPackageName=null`） |

**为什么第三方拿不到 arm64**：WebView 在 Play 上走 App Bundle，按设备分发 split APK，第三方站点存的都是模拟器 x86 包。

**唯一正路是系统 OTA**（厂商定制包 `WebViewGoogleCN` 受保护）。

> 顺带修掉的一个真实 bug：Play 下载长期停在 0%，原因是代理规则把 Google 下载 CDN
> （`gvt1.com` / `gvt2.com` / `gvt3.com` / punycode `xn--ngstr-lra8j.com`）设为 `DIRECT`。
> 改为走代理并热重载后恢复正常。

---

## ⚠️ 为什么 targetSdk 必须是 28

本项目最反直觉的一条约束，来自 `/system/etc/selinux/plat_seapp_contexts`：

```
minTargetSdkVersion=34 → domain=untrusted_app      levelFrom=all
minTargetSdkVersion=29 → domain=untrusted_app_29   levelFrom=all
minTargetSdkVersion=28 → domain=untrusted_app_27   levelFrom=all    ← 只有这条两边都满足
minTargetSdkVersion=26 → domain=untrusted_app_27   levelFrom=user
```

**28 是唯一同时满足两件事的取值：**

- `untrusted_app_27` 兼容域 → 允许从 App 私有目录 `exec` 打包进来的 node 二进制
- `levelFrom=all` → 拿到完整 MCS category，允许 bind TCP 端口

取 27：能 exec，但 category 不全、绑不了端口（Node 报 `EACCES`，很像权限问题）。
取 29+：能绑端口，但失去 exec 权限（`error=13, Permission denied`）。

**改这个值之前先读这一段。**

## 另外两条不能忘

- **`INTERNET` 权限必须有。** 缺了它内核直接拒绝 `socket()`：Kotlin 侧报 `EPERM`，Node 侧报 `EACCES` —— 两个错误都长得像 SELinux 问题，很容易查错方向。
- **签名必须显式固定**（`signingConfigs` 里指定 `storeFile`/`keyAlias`/密码）。只把 keystore 放到 `~/.android/debug.keystore` 不算数：workflow 里的指纹可能完全正确，而 APK 里装的却是另一个证书。用 `apksigner --print-certs` 验产物。

---

## 构建

推到 `main` 自动构建，或手动触发 Actions。APK 作为 artifact 上传（保留 30 天）。

本地构建需要 Android SDK + Gradle 8.9 + JDK 17。

## 路线

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 旧壳引擎看护（崩溃/重启自愈） | ✅ 完成 |
| P0.5 | payload 备份校验（747M / 47,523 文件） | ✅ 完成 |
| P1.0 | 构建链（GitHub Actions → 可安装 APK） | ✅ 完成 |
| P1.1 | 宿主骨架：fork 引擎 + 环境变量 + 稳定状态文件 | ✅ 完成 |
| P1.2 | 端口探测 + 就绪判定（放弃 IPC，理由见下） | ✅ 完成 |
| P1.3 | 内置 WebView，App 内直接对话 | ⚠️ 代码完成，系统 WebView 117 卡住 |
| P3 | 保活：前台服务 + 开机自启 + 冻结检测 | ✅ 完成 |
| P2 | 升级闸门：清单签名 + 白名单 + 回滚 | 待办 |
| P4 | 插件体系 | 待办 |

### 为什么 P1.2 没做 Unix socket IPC

官方文档说「Node IPC 承载启动注入、就绪、致命错误与关闭」，但**该协议的实现位于 `dsh-desktop-host` 包**，而 App 侧使用的 runtime payload 里并不包含这个包。`dsh web` 的 CLI 也没有传入 token 的参数 —— token 由内核自动生成，**stdout 是官方唯一对外暴露的通道**。

所以改为：端口先探测再指定、`--no-open` 消除噪音、就绪判定附加一次探测（但探测仅作诊断，不作判定依据）。

## 设计原则

- **官方内核零改动**：dsh 只当依赖，不打补丁，定制全部走插件。
- **四层独立更新**：Node 运行时 / dsh 内核 / 插件 / 数据记忆。
- **引擎绝不打进 APK**：否则每次升级 dsh 都要重装 App。
- **探针坏了不许连累主流程**：引擎能打印带 token 的 URL 本身就是就绪信号。
- **7×24 必须可靠的放服务器**；必须摸手机的放手机，并接受它随时可能离线。

## 目录

```
docs/design-v2.md     完整设计文档（架构、阶段拆解、实测数据、踩坑记录）
tools/host-guard      root 冻结守护脚本（Magisk service.d 拉起）
app/                  Android 宿主源码
```

## 许可

MIT
