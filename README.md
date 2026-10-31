# dsh-android-host

Android 宿主 App for [dsh](https://github.com/deepseek-ai/dsh) —— 给自建 AI 系统一个自己的手机身体。

**Kotlin + Jetpack Compose** · 包名 `cc.bestly.dsh` · minSdk 24 / targetSdk 28

---

## 现在是什么状态

**P1.0 / P1.1 / P1.2 / P1.4 / P2 / P3 全部完成并上机实测。P1.3（App 内 WebView）代码完成，但被系统 WebView 版本卡住 —— 已用 P1.4 绕开。**

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

## 从浏览器访问完整 UI（P1.4，绕开 WebView 限制）

dsh 的 `--host` **拒绝通配地址**（`0.0.0.0` 被直接拒绝），但允许绑某个**具体接口地址**。
利用这一点把引擎绑到 Tailscale 地址上，界面就能在任何设备的浏览器里打开：

```
--host 100.93.28.42 --trusted-host 100.93.28.42:13081
```

- `pickBindHost()` 优先找 `tun*` / `tailscale` 接口的 IPv4（CGNAT 100.64/10）
- 拿不到则退回 `127.0.0.1`；**绝不退回 `wlan0`**，避免把服务暴露给整个局域网
- `--trusted-host` 必须与 `--host` 一致，否则 `/api` 的浏览器信任栅栏会拒绝（页面能开、功能不通）
- `runtime/host` 与 `runtime/port`、`runtime/token` 一起写，供外部脚本读取

**认证走 cookie 接力**：`GET /?token=<t>` → `303` + `set-cookie: dsh-auth-*`（HttpOnly,
SameSite=Strict）→ 带 cookie 再请求才是 `200`。所以用 curl 验证时必须带 cookie jar，
否则会误判成 401。

实测（2026-10-29，本机 → 8T 经 Tailscale）：

| 项 | 结果 |
|---|---|
| 监听地址 | `100.93.28.42:13081`（不再是 127.0.0.1） |
| 无 token | `401` |
| 带 token 首跳 | `303` + set-cookie |
| 带 cookie 跟随 | `200` / **37,637 字节** / `<title>DeepSeek Harness</title>` / 6 个 script |

**这条路的意义**：绕开系统 WebView 版本限制，而且不牺牲安全性 ——
服务只在加密 mesh 内网可达，不暴露公网、不暴露局域网。

## 升级闸门（P2）：能看 / 能升 / 能退 / 能锁

dsh 还是 beta，官方更新快，所以「能方便地升级」本身是硬需求。
`tools/dsh-upgrade` + `tools/dsh-trust.js` 把这四件事做实：

| 能力 | 命令 | 实测（2026-10-29） |
|---|---|---|
| **能看** | `dsh-upgrade status` | 当前 `0.2.1-rc.2` + 官方 dist-tags（latest=rc.2 / next=rc.4 / alpha=alpha.2） |
| **能升** | `dsh-upgrade upgrade 0.2.1-rc.4` | ✅ 4 分钟完成，引擎 `401` 就绪 |
| **能退** | `dsh-upgrade rollback` | ✅ 16 秒回滚，引擎 `401` 就绪 |
| **能锁** | `dsh-upgrade verify` | ✅ Ed25519 验签 + 关键文件哈希，两组篡改攻击均被抓 |

### 四条硬约束（每条都是踩出来的）

| # | 约束 | 不遵守的后果 |
|---|---|---|
| 1 | **Android 适配 patch 必须重打** | 新版必然以 `No usable native binding found for node-addon-require-builtin-android-arm64` 起不来。上游该模块依赖平台专属 `.node`，而安卓没有产物；这个 App 的 payload 也不带任何 `.node`/`.so`。社区壳把它改成了 js-fallback（走 `--expose-internals`），补丁存于 `docs/patches/` |
| 2 | **`npm install` 必须 `--ignore-scripts`** | `koffi` / `node-pty` 需要 node-gyp 原生编译，安卓无工具链，直接失败 |
| 3 | **副本必须修 SELinux context** | `cp -a` 会丢 MCS category（`s0:c37,c257,c512,c768` → `s0`），引擎起不来 |
| 4 | **npm 装出的是扁平布局** | 社区壳原装是嵌套（依赖在 `dsh/node_modules/`），npm 装在顶层。patch 路径与校验逻辑必须同时兼容两种布局 |

### 闸门已经证明过价值

演练升到 `0.2.1-rc.4` 时：引擎起来了、HTTP 也通，但**完整页面返回 404**；
同一套访问命令在 `rc.2` 上返回 `200 / 37,637B / <title>DeepSeek Harness</title>`。

**结论：当前不该升 rc.4。** 没有这道闸门，盲目追新就会撞上这个坑还得手工救。

### 信任根

私钥只在开发机（另存 Vaultwarden），设备只持公钥（能验不能签）。
详见 [`docs/trust.md`](docs/trust.md)。

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
| **P1.4** | **引擎绑 Tailscale 地址，浏览器直连完整 UI** | **✅ 完成（绕开 P1.3 限制）** |
| P3 | 保活：前台服务 + 开机自启 + 冻结检测 | ✅ 完成 |
| **P2** | **升级闸门：能看 / 能升 / 能退 / 能锁** | **✅ 完成（2026-10-29 实测）** |
| **P4** | **插件体系：两层插件 + 6 个官方插件注册** | **✅ 完成** |
| **P5** | **正式签名 + 密钥进 Vaultwarden** | **✅ 完成（证书 `62:e6:2f:47:…:42:ce:60:09`）** |
| **P6** | **自有插件 `dsh-tool-phone-life`（4 工具）+ `phone-health`（1 工具）** | **✅ 完成（真机验证）** |
| **P7** | **引擎自愈重写（v0.8.1→v0.8.4）+ 三层看护** | **✅ 完成（2026-10-30 真机验收）** |
| **P7.5** | **记忆系统第 0、1 步（引擎自愈 + 收尾三问触发器）** | **✅ 完成（2026-10-30，见 `docs/memory-trigger.md`）** |
| **P8** | **零八从旧壳迁到新壳 + 旧壳退役** | **✅ 完成（2026-10-30 21:20 验收，见 `docs/migration-cutover.md`）** |
| P9 | 记忆系统第 2–4 步（L0 一页纸 / 三个原生工具 / 后台巩固） | 📝 设计完成，第 3 步等一周观测后决定 |
| P10 | 主动性（定时 / 目标 / 技能） | 待办 |

### 当前实机状态（2026-10-30 21:20 实测）

```
App        cc.bestly.dsh  v0.8.5  versionCode 13  uid 10295  MCS category c39
内核       rc.2（运行中），node v26.4.0
引擎       13081（绑 Tailscale 接口，非回环）  401 = 健康
工具数     46（官方 41 + 自有 5）
权限       READ/WRITE_EXTERNAL_STORAGE 均 granted（v0.8.5 补）
签名       正式签名，apksigner verify 通过
登录       DeepSeek 账号 OAuth（非 APIKey）
看护       App watcher（92s 自愈）/ host-guard-v2（root 60s）/ CT106 cronjob（5min）
中继       tg8-relay → 13081（TG 群「456总控室」正常收发）
旧壳       com.deepseek.harness 已 pm disable-user，数据 39MB 保留（退路）
记忆       zero-eight 月记 53 行 / 12,025 字节（基线）
冻结       已解决（ColorOS 三开关按 App 生效，新壳已开）
```

**唯一遗留**：内置 WebView 需 Chrome 119+（系统 WebView 停在 117），
App 内 UI 不可用；日常走 Tailscale 浏览器或 TG 群，引擎本身不受影响。

## 更新纪律（456 定的规矩，2026-10-30）

> **新壳期间，任何改动都要及时更新到仓库，不要攒。**

具体：

- **代码/配置改了** → 同一个提交里更新相关文档
- **实测有新结论** → 写进对应文档，标注「实测」与「未验证」的区别
- **方案被推翻/修正** → 保留原文，另加修正说明（不删历史，只标注）
- **每次提交前扫一遍凭据**：不得出现 API key、token、密码、签名口令、`.credentials.yaml` 内容
- **进度变化** → 更新上面那张路线表与「当前实机状态」

**提交信息用 `type(scope): 说明` 格式**，`type` 取 `feat` / `fix` / `docs` / `chore`。

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
app/                    宿主 App（Kotlin + Compose）
  src/main/java/cc/bestly/dsh/
    MainActivity.kt     界面 + 存储权限申请
    EngineService.kt    前台服务、心跳、健康检查、外部重启入口
    engine/EngineLauncher.kt   引擎 fork、端口探测、generation 代号
    BootReceiver.kt     开机自启
config/                 dsh 配置补丁（cordis.patch.yml）
plugins/                自有插件（phone-life / phone-health）
tools/                  运维脚本（升级闸门 / 看护 / 巡检 / 验收）
docs/                   设计与实测记录（见下方索引）
.github/workflows/      云端构建
```

## 文档索引

**排障与实测（踩过的坑都在这里）**

| 文档 | 内容 |
|---|---|
| [`docs/engine-freeze.md`](docs/engine-freeze.md) | ColorOS 冻结机制与 33 次冻结的原始记录 |
| [`docs/engine-selfheal.md`](docs/engine-selfheal.md) | v0.8.1→v0.8.4 四个 bug 与 generation 代号修法 |
| [`docs/external-watchdog.md`](docs/external-watchdog.md) | 三层看护设计 + host-guard-v2 的六个坑 |
| [`docs/trust.md`](docs/trust.md) | 升级闸门的信任根（Ed25519） |

**记忆系统**

| 文档 | 内容 |
|---|---|
| [`docs/memory-design.md`](docs/memory-design.md) | v1 方案：四层 + 三钩子 + 5 步落地 |
| [`docs/memory-trigger.md`](docs/memory-trigger.md) | 第 1 步「任务收尾三问」+ 零八的回应 |

**换壳迁移（2026-10-30）**

| 文档 | 内容 |
|---|---|
| [`docs/migration-prep.md`](docs/migration-prep.md) | 第 ① 步准备：搬什么、不搬什么、三项能力实测 |
| [`docs/cross-shell-gate.md`](docs/cross-shell-gate.md) | 换壳门（防两个零八）+ 换壳说明 |
| [`docs/migration-cutover.md`](docs/migration-cutover.md) | 第 ③ 步切换：漏了认证那一环 + FUSE 属主判据失效 |
| [`docs/migration-rollback.md`](docs/migration-rollback.md) | **回退手册**（3 步 + 10 项前置检查 + 24h 观察项） |
| [`docs/oldshell-retire.md`](docs/oldshell-retire.md) | 旧壳退役：mailwatch 不能停 + 被拉起两次的两个原因 |

**系统与换系统决策（2026-11-01）**

| 文档 | 内容 |
|---|---|
| [`docs/8t-system-baseline.md`](docs/8t-system-baseline.md) | 8T 系统与权限基线（26KB，12 章）：系统身份、权限三层与两条执行路径、ColorOS 增量层、Oplus Hans 机制、换系统评估 |
| [`docs/correspondence/20261101-换系统决策-给零八.md`](docs/correspondence/20261101-换系统决策-给零八.md) | 零一给零八的决策包（正反两面，不替它判断） |
| [`docs/correspondence/20261101-零八的决策原话-换不换系统.md`](docs/correspondence/20261101-零八的决策原话-换不换系统.md) | 零八的拍板：**现在不换，等条件到** + 三个触发条件 + 判据「不是 ColorOS 有缺点，是 ColorOS 挡住了我们已经决定要做的事」 |
| [`docs/correspondence/20261101-零八的回话-有想说的.md`](docs/correspondence/20261101-零八的回话-有想说的.md) | 转达 456 的回应之后它的回话，含三条可复用判据（「等」只对一方收费 / 有偏好≠有意识 / 不保证比保证更负责） |

**其他**

| 文档 | 内容 |
|---|---|
| [`docs/design-v2.md`](docs/design-v2.md) | 早期架构设计（755 行，含已被推翻的方案，保留原文） |
| [`docs/correspondence/`](docs/correspondence/) | 与零八的往来信件原文 |

**脚本**

| 脚本 | 用途 |
|---|---|
| `tools/dsh-upgrade` | 升级闸门：能看 / 能升 / 能退 / 能锁 |
| `tools/dsh-trust.js` | 信任根签名与校验 |
| `tools/host-guard-v2` | 新壳外部看护（冻结→解冻→restart→冷启动，分级处置） |
| `tools/service.d/60-host-guard-v2.sh` | 上面那个的开机自启（Magisk late_start） |
| `tools/service.d/35-mailwatch.sh` | 零八收件箱值守 v5（动态读 runtime/） |
| `tools/dsh-watchdog-alert.sh` | CT106 侧巡检（正常静默，只报异常） |
| `tools/tgsend-gate.sh` + `tools/install-gate.sh` | 换壳门（awk 原地插入，不重写凭据文件） |
| `tools/dsh-migration-acceptance.sh` | 11-06 一周后验收（记忆增长 + 新壳健康 + 旧壳现状） |
| `tools/host-guard` | 旧壳冻结守护（已随旧壳退役，保留供回退参考） |
| `docs/patches/` | Android 适配补丁（升级时必须重打） |

## 许可

MIT
