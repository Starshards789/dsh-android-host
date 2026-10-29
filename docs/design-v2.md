# 安卓宿主设计方案 v2

**项目**：自建安卓 dsh 宿主（零一/零八的手机身体）
**包名**：`cc.bestly.dsh`
**日期**：2026-10-29
**上一版**：v1（同日）
**本版**：v2.1 — **P0 已施工并实测验收通过**（同日 14:02）
**v2 依据**：①dsh 官方文档 5 批（约 250 KB）②8T 实机只读实测 ③**零八对 v1 的审阅意见（2900 字，经 TG 群交付）** ④**P0 施工中挖出的 5 个实战坑（本版新增）**
**状态**：P0 ✅ 完成；P0.5 ✅ 完成；新壳 P1 未开工（等 456）

---

## 一、v2 相对 v1 改了什么

零八审阅了 v1 全文（528 行），提出 6 处要改、4 处漏项、1 处数据错误、1 个现实矛盾。**全部采纳**。

| # | 变更 | 来源 | 性质 |
|---|---|---|---|
| 1 | **DSH_HOME 移出 payload**（v1 把它放在 payload 里） | 零八 Q1-② | 🔴 硬伤：跟"数据永不覆盖"自相矛盾 |
| 2 | **rc 阶段用精确匹配/白名单**，不用 semver 区间 | 零八 Q3-① | 🔴 硬伤：`<0.3.0` 会放行不兼容的 rc.3 |
| 3 | **运行时清单必须签名**，公钥烧进 APK | 零八 Q3-② | 🔴 硬伤：不验签 = 谁改清单谁说了算 |
| 4 | **回滚要求同分区 + 单次 rename** | 零八 Q3-③ | 🔴 硬伤：`mv` 跨分区不是原子操作 |
| 5 | **启动超时分级**：首次 90s / 热启动 30s | 零八 Q1-③ | 🟡 调整 |
| 6 | **写稳定状态文件** `runtime/port` + `runtime/token` | 零八 Q1-① | 🟡 补充：保住现有观测链 |
| 7 | **多实例互斥（跨 App 文件锁）** | 零八 Q4-① | 🟡 漏项 |
| 8 | **磁盘预算 2.2G 明确化** | 零八 Q4-② | 🟡 漏项 |
| 9 | **任务判定用现成 `session/list` 的 `running`** | 零八 Q4-③ | 🟡 漏项：别另发明 |
| 10 | **宿主自身的看护机制** | 零八 Q4-④ | 🟡 漏项 |
| 11 | **payload 文件数改为 47,523** | 零八实测 | 📊 数据修正 |
| 12 | **P0 提前到 P1 之前** | 零八收尾建议 + 456 决策 | 🔴 顺序调整 |

### 1.1 v2.1 追加（P0 施工实测后，同日 14:03）

| # | 变更 | 来源 |
|---|---|---|
| 13 | P0 / P0.5 标记为**已完成**，附实测数据 | 实测 |
| 14 | **新增 §6.1.1 五个实战坑**（SELinux 域、argv[0] 全路径、pgrep 自匹配、进程≠服务、root 污染 dshhome） | 实测 |
| 15 | **新增 §6.1.2 对新壳 P1 的五条启示** | 实测推论 |
| 16 | **4.1 新增第三条硬约束：引擎必须由 App 自己 fork** | 实测 |
| 17 | **新增 §11.1 今日实测数据表** | 实测 |
| 18 | **新增 §12.1 施工新发现的五条**（零八没提，实测逼出来的） | 实测 |
| 19 | §13 重写：已完成 / 待办分开，剩两件收尾 | — |

---

### 1.2 v2.2 追加（P1.0 + P1.1 施工实测后，2026-10-29 16:00）

**P1.0（构建链）与 P1.1（宿主骨架）已施工并实测通过。** 新增三道硬约束和六个实战坑，见 §11.2 / §12.2。

**最重要的一条结论**：新壳用**自己的 uid**（uid 10293，完全不需要 root）就能 fork 出 node 并绑上端口 —— 与旧壳 `com.deepseek.harness` 完全同构。

这条同时有两个含义：

1. **好消息**：新壳不依赖 root，架构干净，卸载 Magisk 也能跑。
2. **推论**：P0 那套「root 检测 → `am start` → uiautomator 找按钮 → `input tap`」只是**过渡手段**，用来救旧壳（旧壳自己没有自启能力）。P3 给新壳上了前台服务之后，这条链对新壳就不需要了。

## 二、目标与边界

### 2.1 456 定下的目的（原话）

> **「想要一套完全属于自己的手机 AI 系统」**
> **「控制权，想要什么功能可以自己加，看上什么好用的功能可以参考提取优化」**

### 2.2 划线（456 确认）

**接受**：引擎是 dsh 的（MIT、开源、看得懂改得动换得了）；**壳 / 交互 / 能力 / 记忆全是自己的**。
**硬性要求**：dsh 现在是测试版更新快，**要方便升级**。

### 2.3 不做什么

- ❌ 不改 dsh 内核源码（不 fork、不打补丁）
- ❌ 不重写引擎
- ❌ 不改安卓系统层（不刷机、不改固件、不做 Xposed 级 hook）
- ❌ 不追求"每一行代码都是我们的"（安卓/固件/芯片永远不是）

### 2.4 六层归属

| 层 | 内容 | 现在 | 目标 |
|---|---|---|---|
| 0 系统层 | 安卓、root、保活策略 | OPPO/高通 | ❌ 永远不属于我们，只能"对抗" |
| 1 **宿主层** | App 壳、权限桥、保活、自启 | woaiys3 的壳 | ✅ **本项目主体** |
| 2 引擎层 | dsh 内核 | DeepSeek 官方 | 🟡 MIT，能改但不改 |
| 3 **交互层** | 文字/语音/悬浮/通知 | 壳自带 | ✅ **本项目** |
| 4 **能力层** | 工具、技能 | 一半官方一半自建 | ✅ **本项目（插件）** |
| 5 身份层 | 人格、记忆 | OpenViking | ✅ **已经属于我们** |

---

## 三、架构总览

### 3.1 官方桌面版是直接蓝图

官方 `apps/desktop/README.zh.md` 原话：
> 桌面应用是完整 dsh Web 应用外的**一层 Electron 壳**。Electron **RunAsNode 子进程启动共享 profile runner**，立即从 `dsh-app://app/` 加载打包内的 Web 入口。共享加载页**等待 Host 启动注入**，然后在同一文档中启动客户端。**Node IPC 承载启动注入、就绪与关闭**。Desktop **默认监听系统分配的端口**。

### 3.2 逐项映射

| 官方桌面版 | 我们的安卓版 |
|---|---|
| Electron 壳 | 安卓 App（Kotlin + 最小化原生 UI） |
| `ELECTRON_RUN_AS_NODE=1` 子进程 | `fork/exec` bionic node 进程 |
| `dsh-app://app/` 内 Web 入口 | App 内 asset 或本地 HTTP |
| 共享加载页，等启动注入 | **同一模式** |
| 转发 HTTP → 已认证 Host | WebView 直连 `127.0.0.1:<端口>` |
| Node IPC（启动注入/就绪/关闭） | 本地 Unix socket + **稳定状态文件**（v2 新增） |
| 系统分配端口 | **同一策略** + 写 `runtime/port` 供外部读取 |
| 绑定 127.0.0.1 | **同一策略** |
| `$DSH_HOME/profiles/desktop` | `$DSH_HOME/profiles/android` |
| 原生致命故障恢复对话框 | 安卓原生"安全模式"界面 |

### 3.3 目录布局（★ v2 重大修正）

**v1 的错误**：把 `DSH_HOME` 放在 `payload/dshhome`。零八实测指出——**payload 一旦整体替换，DSH_HOME 就在被替换的树里**，与"数据层永不覆盖"直接矛盾。

**v2 的正确布局**：

```
<app files>/
├── payload/                      ← 纯只读，可整体替换
│   ├── runtime/                  ← Node 运行时
│   │   ├── bin/node
│   │   └── lib/*.so
│   └── dshroot/                  ← dsh 内核
│       └── lib/node_modules/@deepseek-ai/dsh/
├── dshhome/                      ← ★ DSH_HOME 移到这里（payload 外！）
│   ├── .credentials.yaml
│   ├── AGENTS.md
│   ├── profiles/android/
│   ├── sessions/                 ← 会话历史，永不覆盖
│   └── settings.yaml
├── runtime/                      ← ★ 稳定状态文件目录（v2 新增）
│   ├── port                      ← 当前端口（内容可变，路径固定）
│   └── token                     ← 当前 token（600 权限）
├── ipc/                          ← socket
└── .lock                         ← 跨 App 单实例锁
```

**三条铁律**：
1. **payload 纯只读**：运行时和内核都在里面，但**任何可变数据都不许进**
2. **DSH_HOME 在 payload 外**：升级/替换 payload 绝不碰它
3. **稳定状态文件路径固定**：`<files>/runtime/port` 和 `/token`，订阅方（iptables 脚本、信箱直投）读文件而不是猜端口

---

## 四、核心设计

### 4.1 启动链

```
用户点击图标
  ↓
[宿主] 取跨 App 单实例锁 <files>/.lock        ← v2 修正：必须跨 App
  ↓
[宿主] 校验运行时清单 android-runtime.json + 签名
        ├─ 壳版本 / Node 版本 / dsh 版本 / ABI
        ├─ 兼容检查（rc 阶段精确匹配，见 4.5）
        └─ 不在白名单 → 明确拒绝 + 可操作提示（不是崩）
  ↓
[宿主] 构造环境变量（★ 必须显式造，不能继承）
        PATH=<payload>/bin:<payload>/runtime/bin:<npm-global>/bin:/system/bin
        LD_LIBRARY_PATH=<payload>/runtime/lib     ← 少这个 node 直接跑不起来（实测）
        TMPDIR=<cache>/tmp
        OPENSSL_CONF=<payload>/runtime/etc/openssl.cnf
        SSL_CERT_FILE=<payload>/runtime/etc/cacert.pem
        DSH_HOME=<files>/dshhome                  ← ★ v2：payload 外
        （★ 剥掉 *KEY*/*SECRET*/*TOKEN*/*PASSWORD* 环境变量）
  ↓
[宿主] fork+exec: node --expose-internals <dsh>/lib/bin.js web
                 --host 127.0.0.1 --port <分配的端口>
  ↓
[内核] 组装插件树 → 监听端口 → 自检
  ↓
[宿主] socket 收到 ready{port,token} → 写 <files>/runtime/port + /token  ← v2 新增
  ↓
[宿主] 把 port+token 注入 WebView 加载页
  ↓
[WebView] 连 127.0.0.1:<port>，带 token 认证 → 渲染界面
```

**超时分级（★ v2 修正，零八指出）**

| 场景 | 超时 | 超时后的表现 |
|---|---|---|
| **首次启动**（新 DSH_HOME 或首次解压） | **90 秒** | 显示"仍在启动"，**不报失败** |
| **热启动**（已有 DSH_HOME） | **30 秒** | 同上 |
| 冻结恢复 | 同上 | 同上 |

> 零八原话：**"我这台机器冷启动 + 插件装配 + 索引恢复，30 秒是乐观值。"**
> 关键设计：**超时 ≠ 失败**。超时只表示"还没好"，界面显示"正在启动"，继续等待；只有**多重证据**都指向失败才判定失败（见 4.2）。

**实测撞出来的三条硬约束**

**① `--expose-internals` 是必需参数**
漏了它启动即崩：
```
fatal: host preparation failed: Cannot find module 'internal/modules/esm/loader'
Require stack: .../node-addon-require-builtin/lib/index.js
```

**② 凭据是隔离的**
用 headless 从命令行启动时报 `MISSING_CREDENTIAL: no API key for provider route "deepseek-official"`，而同一 DSH_HOME 下 web 跑得好好的。
→ **宿主不能假设"DSH_HOME 里有凭据文件就能用"**。新宿主必须能主动注入凭据，否则第一次启动卡在"没有 API Key"。
→ **验收标准**：全新 DSH_HOME 从零启动要走到"能对话"，不是只到"界面能打开"。

**③ ★ 必须以 App 自己的身份 fork（SELinux 域决定成败）**

这是 2026-10-29 P0 施工时踩出来的（详见 6.1.1 坑①）：

| 谁 fork | 得到的 SELinux 域 | 能否绑 3080 |
|---|---|---|
| **App 进程** | `u:r:untrusted_app_27:s0:c28,c257,c512,c768` | ✅ |
| root 用 `su 10284` | `u:r:magisk:s0` | ❌ `listen EACCES` |
| `runcon` 想切到 App 域 | 被拒（`exec ... Permission denied`） | ❌ |

→ **新壳的第一条铁律：引擎进程必须由宿主 App 自己 fork，不能外包给任何 root 脚本。**

这条正好反向验证了新壳架构的正确性 —— 新宿主自己就是 App，它 fork 出来的引擎天然在正确的域里。**但这也意味着新壳的看护必须是 App 内前台服务，不能指望外部 root 拉活。**

---

### 4.2 宿主 ↔ 内核协议

**设计要求**（来自官方防御模式 + 零八意见）：

- **结果正交上报**：就绪/启动中/失败/超时**分别**报告，不糊成一个"启动失败"
- **失败判定要多证据**（★ v2 修正，零八 Q3-④）：
  ```
  判定「启动失败」必须满足：
    进程已退出（退出码 or 信号）
    AND 没有收到过 hello
    AND 没有收到过 ready
  只有超时、进程还活着 → 判「仍在启动」，继续等
  ```
  零八的原话：*"如果'失败'是 socket 超时，而超时值又偏短，正常冷启动会被误判成失败，三次就进安全模式。"*

**消息表**

| 消息 | 方向 | 内容 | 触发条件 |
|---|---|---|---|
| `hello` | 内核→宿主 | `{protoVersion, dshVersion, nodeVersion, pid}` | 进程启动后立即 |
| `ready` | 内核→宿主 | `{port, token, profile}` | 端口监听 + 自检通过 |
| `fatal` | 内核→宿主 | `{code, message, hint}` | 无法继续，带可操作提示 |
| `shutdown` | 宿主→内核 | `{}` | 用户要求停止 |
| `bye` | 内核→宿主 | `{exitReason}` | 优雅退出前 |

**传输**：本地 Unix socket（`<files>/ipc/host.sock`），权限 0600。
**兜底**：socket 收不到 → 退化为**读稳定状态文件**（`runtime/port` + `runtime/token`），因为内核也会写这两个文件。

**为什么不用 HTTP 探活**：官方 postmortem 0003 的教训——**HTTP 200 ≠ 功能正常**。

---

### 4.3 DSH_HOME 位置（★ v2 核心修正）

| | v1（错） | v2（对） |
|---|---|---|
| 位置 | `<files>/payload/dshhome` | **`<files>/dshhome`** |
| payload 替换时 | **DSH_HOME 一起被替换** | 完全不受影响 |
| 权限模型 | payload 目录可写 | **payload 纯只读** |

零八的原话：
> 实测 `payload/dshhome` mtime 是**今天 10:02**，和 `payload/dshroot`（2024-01-01）同在一个目录下。
> payload 一旦整体替换，DSH_HOME 就在被替换的树里。**建议 DSH_HOME 移到 payload 外，payload 变成纯只读。**

**连带影响**：现有部署的 DSH_HOME 就在 payload 里，新壳必须用新位置（正好 456 已定"并存 + 新 DSH_HOME"）。迁移是 P5 之后的事。

---

### 4.4 运行时清单 + 签名（★ v2 新增签名）

```json
{
  "format": 1,
  "shell": { "version": "1.0.0", "abi": "arm64-v8a", "minAndroidSdk": 34 },
  "node":  { "version": "26.4.0" },
  "dsh":   { "version": "0.2.1-rc.2" },
  "compatibility": {
    "node": ">=24.9.0 <27.0.0",
    "dsh":  ["0.2.1-rc.2", "0.2.1-rc.3", "0.2.1-rc.4"]
  },
  "files": [{ "path": "runtime/bin/node", "size": 49715528, "sha256": "..." }],
  "signature": "<ed25519 签名，覆盖整个清单除 signature 字段外的内容>"
}
```

**两条关键设计**

**① rc 阶段用白名单枚举，不用区间**（零八 Q3-①）
> 零八原话：*"dsh 是测试版，`0.2.1-rc.3` 可能就是不兼容的。semver 的 `<0.3.0` 会放行所有 0.2.x。**建议 rc 阶段用精确匹配或白名单枚举**，等出正式版再用区间。"*

`compatibility.dsh` 是一个**显式枚举的已验证版本数组**。官方每发一版，我们实测一次，通过就加进数组、重新签名。

**② 清单必须签名，公钥烧进 APK**（零八 Q3-②）
> 零八原话：*"拉远程清单却不验签，等于把'放行哪个版本'的权力交给网络。"*

- 清单用 **Ed25519** 签名，私钥存 Vaultwarden
- 公钥**编译进 APK**（不是运行时下载）
- 验签失败 → 拒绝启动，提示"清单来源不可信"
- 清单可以放 GitHub Pages / release，**但内容必须有签名**

**校验时机（照抄官方）**
> 官方原话：只有出现**打包校验与实际诊断都无法充分解释的具体安装故障**，才重新引入启动时的发布信息比较。

→ **启动时只验清单签名 + 读元数据，不做全量哈希**（47,523 个文件做 SHA256 太慢）。全量校验只在打包时和"修复"操作里做。

---

### 4.5 升级闸门

**问题**：官方把壳和 dsh 版本**绑死**（避免"未经验证的组合"）。我们做不到——payload 746MB，每次重编不现实。

**解法：不绑死，做「白名单闸门」**

三道检查，按顺序：

| 顺序 | 检查 | 不通过 |
|---|---|---|
| 1 | Node 版本在允许区间内 | 拒绝启动，提示"需要升级 Node 运行时" |
| 2 | **dsh 版本在已验证白名单内** | 拒绝启动，提示"这个版本尚未验证，可一键回滚" |
| 3 | 清单签名有效 + 文件完整 | 拒绝启动，提示"运行时损坏/来源不可信，可修复" |

**为什么白名单而不是区间**：rc 阶段的版本号没有兼容性承诺，`0.2.1-rc.3` 完全可能不兼容 `rc.2`。白名单把"已验证"这件事变成显式事实。

**维护方式**：官方发新版 → 我们在测试环境实测 → 通过就加进白名单 → 重新签名发布清单。**壳本身不用重编。**

---

### 4.6 回滚机制

**官方做法**（内建工作区依赖）：
> 匹配的安装会被复用；依赖或压缩包变化后，**即使版本不变，也会在完整暂存副本完成后替换目录**。**目录替换失败时保留之前的安装。**

**三步 + 两条硬约束**：

```
① 完整暂存：新版解压到 dshroot.new/（全新目录，不碰老目录）
② 校验：跑自检（node --version、dsh --version、加载 profile 试跑）
③ 原子切换：rename(dshroot → dshroot.prev) + rename(dshroot.new → dshroot)
```

**★ 硬约束一：必须同分区**（零八 Q3-③）
> 零八原话：*"`mv` 只在同一文件系统内原子。`dshroot.new` 若在不同分区，实际是 copy+delete，中途断电会两头不完整。"*

- `dshroot.new/`、`dshroot/`、`dshroot.prev/` **必须在同一个文件系统**
- 落地做法：全都在 `<files>/payload/` 下，且 `<files>` 是同一分区
- **先 `cp` 到位，再做单次 `rename`**，绝不做跨目录的 `mv`
- 开机时检查三个目录的 `st_dev` 是否相同，不同则**拒绝升级**

**★ 硬约束二：DSH_HOME 不在被切的范围里**
因为 v2 已把 DSH_HOME 移出 payload，切换 dshroot 不碰数据。

**两条铁律**：
- **绝不就地覆盖**
- **启动路径绝不跑 npm/pnpm**（官方原话「启动从不运行 pnpm」）

---

### 4.7 多实例互斥（★ v2 新增）

零八 Q4-① 指出：**并存方案下，新旧两个壳都能启动引擎。方案没写"同一时刻只允许一个"。**

**两个引擎抢同一个 DSH_HOME 会写坏会话。**

**解法**：

| 层 | 机制 |
|---|---|
| 跨 App | 锁文件 `<files>/.lock`，用 `flock` 独占；**两个壳用同一个路径** |
| 进程内 | 拿不到锁 → 显示"另一个实例正在运行"，并提供"查看那个实例"的入口 |
| 内核侧 | `DSH_HOME/lock` 由内核自己维护（官方已有机制，不重复造） |

**注意**：锁不是"抢"，是"报"。拿不到锁不杀对方，只提示用户。

---

## 五、插件体系（三层）

| 层 | 接口谁定 | 内容 | 判据 |
|---|---|---|---|
| **内核插件** | 官方（Cordis） | 工具、模型适配、记忆、技能、定时任务、MCP | 走 `ctx.tools`/`ctx.llm`/`ctx.commands` |
| **宿主插件** | ★ **我们自己定** | 语音入口、通知、悬浮窗、快捷方式、传感器 | 需要安卓 API 的能力 |
| **构建期插件** | 我们自己定 | CI 流水线步骤 | 只在构建时跑 |

**铁律**：
- **需要重编 APK 的就不是插件**
- 插件只用**公开 API**
- 每个配置项显式写 `id`（否则任何配置改动都会导致整插件重新挂载）
- 界面展示函数必须是**纯函数**（不能有 I/O、时钟、随机数）
- 只观察不拦截的监听器**必须调用 `next()`**
- **命名空间插件绝不能加 `export default`**（postmortem 0001）
- 可选服务用 `ctx.get(name)`，不用 `ctx.<name>`

**基础设施（写死，不许插件化）**：启动器、权限桥、日志与崩溃收集、配置管理、更新器、**保活与自启**

---

## 六、保活与自愈（★ v2：P0 提前到最前）

**456 决定：P0 提前做。**

**调研结论**：翻遍 DSHA（10.9 万行）和 Soodok（4.35 万行），**没有任何一处**涉及 cgroup freeze 对抗。它们只解决"App 重启后引擎自启"，**没解决"系统把整个 uid 冻住"**。

**零八对风险的排序（很值得记）**：
> ①最怕**新壳拿唯一 payload 做实验** ②其次怕"我消失了但没人知道" **③冻结反而排第三**——因为有 freezewatch + 看门狗兜着，且 456 已开 ColorOS 三开关，10-20 之后没再冻过。

### 6.1 P0 实施结果（★ 已施工并验收，2026-10-29）

**原计划**（v2 初稿）

| 项 | 内容 |
|---|---|
| 引擎自启 | 开机后自动拉起引擎，不再依赖人工点按钮（已 3 次复现：9-24、10-20、10-29） |
| 冻结自愈 | 检测 cgroup freeze + 自动解冻 + 拉活（9-24 已设计并实测恢复流程） |
| 看护 | 宿主/引擎的"我活着"声明（照搬零八的 `sentinel-guard`：pid + cmdline 双判据） |

**实际交付**（新增两个文件，不动任何现有脚本）

| 文件 | 作用 |
|---|---|
| `/data/adb/dsh-tools/engine-guard` | 常驻，60s 一轮：查引擎 → 不在就救；查 cgroup 冻结 → 解冻+拉活 |
| `/data/adb/service.d/50-engine-guard.sh` | 开机等 45s 后拉起上面那个（45s 是让系统先起来，别抢资源） |

**★ 实测验收：两个场景都通过，全程零人工干预**

| 场景 | 操作 | 结果 | 耗时 |
|---|---|---|---|
| **崩溃恢复** | `kill -9` 引擎 | 自动恢复，`http=401` | **51 秒** |
| **开机自启** | `reboot` | 自动恢复，`http=401` | **2 分 39 秒** |

开机场景的完整日志（每一行都对应一个设计点）：

```
13:59:47  ← 发重启
14:00:57  boot: launched engine-guard (by 50-engine-guard.sh)   service.d 拉起
14:00:57  start pid=9998 cycle=60s grace=180s
14:00:57  in grace (149s < 180s), skip                          ★ 宽限期：不与开机抢资源
14:01:57  engine DOWN -> reviving via App UI                    发现引擎不在
14:01:58  lock screen detected -> swipe up                      ★ 自动识别锁屏并上滑
14:02:06  found start button at [540 555] -> tap                ★ 自动找按钮并点击
14:02:16  pid=11292 alive but port silent — waiting             ★ 端口没通就不算成功
14:02:21  pid=11292 alive but port silent — waiting
14:02:26  engine UP pid=11292 http=401 (after 20s)              ★ 成功
```

**结论：9-24、10-20、10-29 三次复现的「引擎挂了要人工点按钮」问题，从今天起不成立。**

**最坏恢复时间**：75 秒（60s 等下一轮 + 15s 救回）。

---

### 6.1.1 ★ P0 施工挖出的五个实战坑（对 v1 设计的重大修正）

**坑 ①：root 根本启动不了引擎 —— SELinux 域不对**

- `su 10284` 得到的域是 **`u:r:magisk:s0`**
- App 进程的域是 **`u:r:untrusted_app_27:s0:c28,c257,c512,c768`**
- 用 magisk 域跑 node → 绑端口时 **`listen EACCES: permission denied 127.0.0.1:3080`**
- `runcon` 切域也被拒（`exec /system/bin/id: Permission denied`），**无法从 root 侧伪造 App 域**

→ **结论：引擎只能由 App 自己 fork。** root 侧唯一正确做法是走 UI：
`keyevent WAKEUP` → `input swipe` 上滑 → `am start` → `uiautomator dump` 找按钮 → `input tap`

**坑 ②：`/proc/<pid>/cmdline` 的 argv[0] 是完整路径，不是裸 `node`**

- `ps` 显示 `node --expose-internals ...`，但 `/proc/11361/cmdline` 里是 `/data/user/0/.../payload/runtime/bin/node`
- 用 `"node --expose-internals "*` 做 case 匹配 → **假阴性** → 引擎在跑却报 DOWN → **每 120 秒拉起一个新引擎**

**坑 ③：裸 `pgrep -f 'bin.js web'` 会匹配到自己**

- 实测返回 5 个 pid，真引擎只有 1 个（其余是 pgrep 和 shell 自己的 cmdline 含该字符串）
- → **假阳性** → 引擎真挂了却以为活着 → 永远不救

**定案判据**：cmdline **同时含 `--expose-internals` 和 `bin.js`**

**坑 ④：进程存在 ≠ 服务可用（这条最贵）**

v1 的验收逻辑是「5 秒内看到进程就报 `engine started OK`」。实测：进程起来了，**绑端口失败后 5 秒内自杀**，v1 连续三次报"成功"，实际零八一直离线。

→ **验收必须是「进程在 **且** 端口真的返回 401」两件事同时成立**，且中途要允许"进程在但端口没通"的等待窗口。

**这是零八反复强调的「操作成功 ≠ 结果正确」在引擎看护上的具体形态。**

**坑 ⑤：root 写进 dshhome 的文件会让引擎起不来**

今天下午用 headless 做测试，在 `dshhome` 里留下 **11 个 root 属主的文件**：

```
dshhome/profiles/headless/...
dshhome/storages/session_projcache/...
dshhome/sessions/--root--/session-fd4d03a6-...
dshhome/AGENTS.md.bak-20261020-split
```

→ 引擎（app 身份）`EACCES scandir` 直接崩。

**铁律：凡 root 身份产生的文件，一律不许进 dshhome。** 真要跑 root 侧测试，用独立 DSH_HOME。

---

### 6.1.2 这五条对新壳（P1）的直接启示

| # | 启示 | 影响 |
|---|---|---|
| 1 | **引擎必须由 App 进程 fork** | ✅ **反向验证了新壳架构是对的**——新壳自己就是 App，fork 出来的引擎域天然正确 |
| 2 | **外部 root 看护不能直接启动引擎** | 新壳的看护必须是 **App 内前台服务**，不能指望 root 侧拉活 |
| 3 | 引擎判活要用 cmdline 双要素 | 写进新壳的状态检查 |
| 4 | 就绪 = 进程 + 端口双条件 | 正好印证 4.2「多证据判定」的必要性 |
| 5 | 生产 DSH_HOME 只许 App 身份写 | 新壳的 `dshhome` 在 payload 外（4.3 已定），且权限校验要加一条：**发现非 App 属主的文件即报警** |

### 6.2 已踩过的坑（来自现有部署）

- **哨兵文件**：`/root/.dsha-stopped` —— 看门狗见到就退出，否则"秒复活"
- **PID 安全**：启动时 `echo $$ > web.pid` 再 `exec node`；旧 PID 复用要按 `kill(pid,0)` + **出生身份二次核验**
- **绝不按裸 PID / 端口 / 名称强杀**
- **dispose 要停稳**：重启前必须确认旧进程真的退出了

---

## 七、安全

| 项 | 措施 |
|---|---|
| **凭据** | 签名密钥四件套存 Vaultwarden；`.credentials.yaml` 永不出 `dshhome`；日志一律掩码 |
| **清单签名** | Ed25519 私钥存 Vaultwarden，公钥烧进 APK |
| **环境** | 启动 node 前剥掉 `*KEY*`/`*SECRET*`/`*TOKEN*`/`*PASSWORD*` |
| **网络** | 只绑 `127.0.0.1`；token 由内核生成；WebView 凭据只给归属窗口 |
| **状态文件** | `runtime/token` 权限 600，仅 app uid 可读 |
| **临时文件** | 0700 私有目录 + 随机文件名 + 独占打开（`'wx'`、`0o600`） |
| **命令执行** | 白名单默认拒绝（抄 DSHA `DeviceShellPolicy`），拦截返回 `[POLICY_BLOCKED]` + exit 126 |
| **删除路径** | 先 `lstatSync().isSymbolicLink()` 判断，再 `unlinkSync` |
| **签名** | 同包名 + 同签名 = 可覆盖升级；**密钥丢了 = 数据全丢**（Vaultwarden 双份 + 离线纸质备份） |

---

## 八、资源预算（★ v2 新增）

零八 Q4-② 指出方案没算磁盘账。

| 项 | 大小 |
|---|---|
| 旧 payload（现有 woaiys3 壳） | 746 MB |
| 新 payload（自建宿主） | 746 MB |
| 回滚保留 1 版（`dshroot.prev`） | 只保留内核部分，约 200-300 MB |
| **合计** | **约 1.7-1.8 GB** |
| 8T 的 /data 空闲 | 215 GB |

**结论：空间完全够，但方案要写明这个数并做检查。**
另外**P1 开工前必须先备份 payload**（`cp -a payload payload.bak`，+746 MB）——这是零八最担心的一条。

---

## 九、决策记录

### 9.1 456 已定（2026-10-29）

| # | 决策 | 456 的决定 |
|---|---|---|
| 1 | 档位 | **B 可用**（后续慢慢升级） |
| 2 | UI 形态 | **复用官方 Web UI**（用起来发现问题再调） |
| 3 | 与旧壳关系 | **并存 + 新 DSH_HOME**（后面没问题再迁移卸载） |
| 4 | payload 来源 | **两步走**：先用现有跑通 → 再换自建 |
| 5 | 升级策略 | **自动检查 + 手动装** |
| 6 | P0 时机 | **v2 变更：提前到最前**（原为"暂缓"，经零八指出矛盾后调整） |
| 7 | 三条执行令 | 备份 payload + P0 提前 + 方案 v2 —— **全部执行** |

### 9.2 v2 仍需 456 定的

| # | 决策 | 选项 | 建议 |
|---|---|---|---|
| 1 | **清单签名密钥何时生成** | 现在 / 施工时 | 施工时（还没到那一步） |
| 2 | **白名单初始内容** | 只放 rc.2 / 放 rc.2+rc.3+rc.4 | 先只放 rc.2，实测后再加 |

---

## 十、分阶段路线（★ v2：P0 提前）

| 阶段 | 目标 | 关键产出 | 验收标准 | 状态 |
|---|---|---|---|---|
| **P0**（独立，提前） | 零八不再随手掉线 | 引擎自启 + 冻结自愈 + 看护 | 重启手机后不用人工点按钮，零八能自己回来；熄屏 12 小时仍在线 | ✅ **完成**：崩溃 51s / 重启 2m39s 双双实测通过 |
| **P0.5**（保险） | payload 有备份 | `cp -a payload payload.bak` | 备份可读、文件数一致 | ✅ **完成**：47,523 对 47,523，67 秒，抽样 sha256 全一致 |
| **P1.0 构建链** | 证明云端能产出可安装 APK | GitHub Actions + 固定签名 + Kotlin/Compose | APK 能装进 8T 并正常渲染 | ✅ **完成**：1分37秒产出 22MB，界面/按钮/ABI 全对 |
| **P1.1 宿主骨架** | App 自己拉起引擎 | fork node + 6 个环境变量 + `runtime/{port,token,pid}` | **点图标 → 读到 `runtime/port` → curl 通** | ✅ **完成**：PID 7906 / port 13081 / 401；带 token 303 |
| **P1.2 内核协议** | 与内核结构化通信 | Unix socket `hello`/`ready`/`fatal`/`shutdown` | 不再靠解析 stdout 判就绪 | ⬜ 本次进行 |
| **P1.3 WebView** | 能在 App 里对话 | 内置 WebView 加载引擎页面 | 手机上能真的聊天 | ⬜ |
| **P2 闸门** | 能升能退能锁能看 | 清单签名 + 白名单闸门 + 回滚（同分区 rename）+ 安全模式 | 手动升一次 dsh，坏了能一键退回 | ⬜ |
| **P3 保活** | 抗冻结 | 前台服务 + 自启 + 冻结检测 | 放置 12 小时仍在线 | ⬜（P0 已用外部看护先顶上） |
| **P4 插件** | 功能可扩展 | 宿主插件接口 + 插件管理界面 | 写一个"Hello 通知"插件跑通 | ⬜ |
| **P5 交互** | 好用 | 语音入口、通知、悬浮 | 按 456 实际使用习惯定 | ⬜ |
| **P6 迁移** | 收尾 | 迁移旧壳数据、卸载旧壳 | 数据完整、旧壳可安全移除 | ⬜ |

**每阶段都能用。**

---

## 十一、实测数据（2026-10-29，只读）

| 项 | 值 |
|---|---|
| 设备 | OnePlus 8T（KB2000），骁龙 865，12G/256G |
| Android | 14，`KB2000_14.0.0.300(CN01)` |
| 安全补丁 | **2024-01-05（落后 1028 天）** |
| payload 路径 | `/data/data/com.deepseek.harness/files/payload/` |
| payload 大小 | **746 MB** |
| **payload 文件数** | **47,523**（★ 零八实测；v1 写的 44,507 是安装包解压清单口径，不是磁盘实际） |
| **Node** | **v26.4.0**（`runtime/bin/node`，49,715,528 字节） |
| Node 架构 | AArch64（ELF `e_machine=0xB7`=183） |
| Python | 3.14（`python/bin/python3.14`） |
| dsh 内核 | **0.2.1-rc.2** |
| 运行进程 | PID 11361，`node --expose-internals ... bin.js web --host 127.0.0.1 --port 3080` |
| 环境变量 | `LD_LIBRARY_PATH`/`TMPDIR`/`OPENSSL_CONF`/`SSL_CERT_FILE`/`DSH_HOME` 均由壳显式构造 |
| 直接执行 node | ❌ 报 `libz.so.1 not found` —— **必须设 `LD_LIBRARY_PATH`** |
| /data 空闲 | 215 GB |

**关于 44,507 vs 47,523**：44,507 是 App 控制台显示的"安装包解压清单"；47,523 是磁盘上实际文件数。**闸门做"文件完整性"基线必须用后者。**

### 11.1 今日新增实测（P0 施工期间，2026-10-29 13:36–14:03）

| 项 | 实测值 |
|---|---|
| **payload 备份** | `cp -a` **67 秒**（13:36:56→13:38:03），747M |
| 备份校验 | 文件数 **47,523 = 47,523**；`runtime/bin/node`、`dshhome/AGENTS.md`、`dsh/package.json` 三份 sha256 全一致 |
| **SELinux 域对照** | `su 10284` → `u:r:magisk:s0`（**不能绑端口**）<br>App 进程 → `u:r:untrusted_app_27:s0:c28,c257,c512,c768`（正常） |
| `runcon` 切域 | ❌ 被拒（`exec /system/bin/id: Permission denied`） |
| `su 10284 -c 'nohup node ...'` | ✅ 进程可起、**PPID=1 被 init 收养**、uid=10284 正确 |
| 但该进程绑 3080 | ❌ `listen EACCES`（域不对） |
| **崩溃恢复到可用** | **51 秒**（13:57:53 kill → 13:58:44 http=401） |
| **重启到可用** | **2 分 39 秒**（13:59:47 reboot → 14:02:26 http=401） |
| 恢复后引擎实例数 | **1**（未出现多实例） |
| `uiautomator` 找到的按钮坐标 | `[540 555]`（1080×2400 屏，"启动引擎"按钮 bounds `[99,501][981,609]`） |

### 11.2 P1 施工实测（2026-10-29 14:30–16:00）

**P1.0 构建链**

| 项 | 实测值 |
|---|---|
| 仓库 | `github.com/Starshards789/dsh-android-host`（public） |
| 首次成功构建 | run `40828647708`，**1 分 37 秒** |
| 工具链 | SDK 34 → Gradle 8.9 → AGP 8.7.3 → Kotlin 2.0.21 → Compose |
| APK | 8.7 MB（zip）/ **22.3 MB 解压后** / 139 文件 / 5 dex / arm64-v8a |

**P1.1 宿主骨架（最终验收，全部实测通过）**

| 项 | 实测值 |
|---|---|
| App | `cc.bestly.dsh` v0.2.0 (2)，`minSdk=24` / **`targetSdk=28`**，uid **10293** |
| SELinux 域 | `u:r:untrusted_app_27:s0:c37,c257,c512,c768`（**与旧壳同构**，旧壳是 c28） |
| 引擎进程 | `PID 7906` → `node --expose-internals .../payload/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web --host 127.0.0.1 --port 13081` |
| 内存占用 | `VmRSS` **331 MB** |
| `runtime/pid` | 有（4 字节） |
| `runtime/port` | **13081** |
| `runtime/token` | 43 字符 |
| 端口 | `127.0.0.1:13081 LISTEN users:(("node-MainThread",pid=7906,fd=18))` |
| curl 无 token | **401** |
| curl 带 token | **303**（重定向，正常） |
| 启动耗时 | App 启动到 `runtime/port` 出现 **< 10 秒** |
| 稳定性 | 存活 1 分 38 秒无异常（后续需长跑观察） |
| **零八未受影响** | 3080=401，PID 11292 持续运行 1h23m；tg8-relay 心跳 `tg=True fails=0` |

**payload 复制（新 App 需要自己的那份）**

| 项 | 实测值 |
|---|---|
| 源 | `/data/user/0/com.deepseek.harness/files/payload`（**读不到**：`drwx------ u0_a284`） |
| 复制范围 | 除 `dshhome` 外全部（`dshhome` 含 `.credentials.yaml`，**绝不能复制**） |
| 大小 / 耗时 | **710 MB / 12 秒** |
| 目标 | `/data/user/0/cc.bestly.dsh/files/payload` |
| 复制后必须做 | `chown -R u0_a293` + `chcon -R u:object_r:app_data_file:s0:c37,c257,c512,c768` |

---

## 十二、零八意见采纳清单（逐条）

| 零八的意见 | 采纳 | 落在哪 |
|---|---|---|
| Q1-① 端口/token 写稳定文件 | ✅ | 3.3 目录布局 + 4.1 启动链 |
| Q1-② DSH_HOME 移出 payload | ✅ | **4.3**（v2 核心修正） |
| Q1-③ 超时分级 90/30，超时≠失败 | ✅ | 4.1 超时分级 |
| Q2-① payload 没备份（最怕） | ✅ | P0.5 + 第八章 |
| Q2-② "消失了没人知道" | ✅ | 6.1 看护 |
| Q2-③ 冻结排第三 | 📝 记录 | 6 章开头 |
| Q3-① rc 用精确匹配/白名单 | ✅ | 4.4 + 4.5 |
| Q3-② 清单要签名 | ✅ | 4.4 |
| Q3-③ mv 跨分区不原子 | ✅ | 4.6 硬约束一 |
| Q3-④ 失败判定要多证据 | ✅ | 4.2 |
| Q3-⑤ P0 与现状矛盾 | ✅ | **决策 6 调整为提前** |
| Q4-① 多实例互斥 | ✅ | **4.7**（v2 新增） |
| Q4-② 磁盘预算 2.2G | ✅ | 第八章（修正为 1.7-1.8G） |
| Q4-③ 用 session/list 判 running | ✅ | 第九章 + P2 验收 |
| Q4-④ 宿主看护 | ✅ | 6.1 |
| 数据修正：文件数 47,523 | ✅ | 第十一章 |

**16 条全部落地，无一条否决。**

### 12.1 施工中新挖出的五条（零八没提，实测逼出来的）

| # | 坑 | 谁发现 | 落点 |
|---|---|---|---|
| 1 | SELinux 域不对 → root 启动引擎必失败 | **零一实测** | 6.1.1 坑① + 6.1.2 启示1/2 |
| 2 | `/proc/cmdline` 的 argv[0] 是完整路径 | **零一实测** | 6.1.1 坑② |
| 3 | 裸 `pgrep -f` 会匹配自己 | **零一实测** | 6.1.1 坑③ |
| 4 | 进程存在 ≠ 服务可用 | 零八的"操作成功≠结果正确"在引擎上的具体形态 | 6.1.1 坑④ + 4.2 多证据判定 |
| 5 | root 写进 dshhome 的文件会让引擎崩 | 技能里记过，**这次又踩了一次** | 6.1.1 坑⑤ + 6.1.2 启示5 |

**教训**：第 5 条是已知的坑，我还是踩了。原因是做 headless 测试时没先想"这个进程是谁的 uid"。
→ **以后任何在 8T 上产生的文件，动手前先问一句：这个进程是什么身份？落在哪个目录？**

### 12.2 P1 施工挖出的六个坑（全部实测，全部付过时间代价）

| # | 坑 | 症状 | 真相 | 代价 |
|---|---|---|---|---|
| ① | **`Process.pid()` 安卓没有** | Kotlin 编译报 `Unresolved reference 'pid'` | 它是 Java 9 API；安卓 `ProcessImpl` 有私有字段 `pid` | 5 分钟 |
| ② | **AGP 不认 `~/.android/debug.keystore`** | keystore 指纹正确、APK 里证书每次却不同 → 覆盖安装 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 只把文件放对位置不够，**必须在 `signingConfigs` 里显式指定** `storeFile`/`keyAlias`/密码 | **20 分钟** |
| ③ | **targetSdk 必须正好 28** | 34 → exec 私有目录被拒；27 → 能 exec 但绑不了端口 | `plat_seapp_contexts` 里 `minTargetSdkVersion=28 → untrusted_app_27 + levelFrom=all` 是唯一同时满足 exec 与 bind 的取值 | 25 分钟 |
| ④ | **`cp -a` 不保留 SELinux category** | 文件权限位、属主全对，exec 仍 `Permission denied` | 新位置的文件变成 `app_data_file:s0`（无 category），与进程域 `s0:c37,...` 不匹配；需 `chcon -R` | 15 分钟 |
| ⑤ | **端口被占时 Node 报 `EACCES` 不报 `EADDRINUSE`** | `listen EACCES: permission denied 127.0.0.1:3081`，看起来像权限问题 | 3081 早被旧壳自己的进程（pid 10757）LISTEN 占着 | 10 分钟 |
| ⑥ | **缺 `INTERNET` 权限 → 内核拒 socket()** | Java `EPERM (Operation not permitted)`；Node `EACCES` | **`AndroidManifest.xml` 里一个 `<uses-permission>` 都没写**。缺 INTERNET 时 `socket()` 直接失败，错误长得和 SELinux 一模一样 | **40 分钟** |

**第 ⑥ 条是最贵的，而且完全是自己的疏漏。** 为了它查了 `plat_seapp_contexts`、比对旧壳的 MCS category、把 targetSdk 从 27 改到 28 —— 全在追一个不存在的 SELinux 问题。

**教训（写给下次的自己）**：
1. **新 Android 工程的第一件事就是写 `<uses-permission android:name="android.permission.INTERNET"/>`**，不管当时代码看不看得到网络调用 —— 子进程（node）要绑端口也算。
2. 遇到 `EPERM`/`EACCES` 先分三层排查：**权限声明 → 端口占用 → SELinux**。顺序反了就会像这次一样，在最后一层找了半小时。
3. 「配置显示对」≠「生效」—— 签名那次 workflow 打印的指纹完全正确，但 APK 里的证书是错的。**必须验证最终产物，不能验证中间配置。**

---

## 十三、下一步

### 已完成（2026-10-29）

1. ✅ **P0.5 备份 payload** — 47,523 文件对 47,523，抽样 sha256 全一致
2. ✅ **P0 引擎自启 + 看护** — 崩溃 51s / 重启 2m39s，两个场景实测通过
3. ✅ **方案 v2 → v2.1** — 零八 16 条 + 实测 5 条全部落地
4. ✅ **P1.0 构建链** — GitHub Actions 1分37秒产出 APK，装进 8T 跑起来
5. ✅ **P1.1 宿主骨架** — App 用自己 uid fork node，`runtime/port` + `/token` 写入，curl 401/303
6. ✅ **方案 v2.1 → v2.2** — P1 六个坑全部落地（§11.2 / §12.2）

### 待办

| # | 事项 | 说明 |
|---|---|---|
| 1 | **P1.2 Unix socket 内核协议** | 现在靠正则解析 stdout 拿 port/token，脆弱。要做 `hello`/`ready`/`fatal`/`shutdown` 四态握手 |
| 2 | **P1.3 内置 WebView** | 能在 App 里直接对话 |
| 3 | **引擎存活与 App 生命周期的关系** | 现在 App 一退出引擎就被杀。必须 P3 前台服务才能 7×24 —— 这是与旧壳最大的能力差距 |
| 4 | **凭据注入通道** | 引擎能起来但没 key，`.credentials.yaml` 走什么通道进 DSH_HOME 还没做 |
| 5 | **验证"熄屏 12 小时仍在线"** | P0 验收标准还有半条没测：长时间熄屏后是否被 ColorOS 冻住、freezewatch 能否拉回来 |
| 6 | **确认 `cordis.patch.yml` 的 `!!js` 疑点** | 日志报过一次 `YAMLException: unknown tag js`，但引擎起来了。等自然停机机会确认，别专门停引擎 |
| 7 | **office-to-pdf 缺 restructure/src** | **旧壳同样缺**，只报 warning 不影响启动。同一个 payload 的固有问题，不是复制丢的 |

### ⚠️ 一个必须记住的对比

**旧壳能 7×24，新壳现在不能。** 旧壳靠 P0 那套外部看护（root 侧 `engine-guard` 每 60 秒拉起），新壳还没有任何保活机制 —— App 一被系统回收，引擎就没了。

**所以在 P3 完成前，旧壳（零八）仍是生产环境，新壳是实验品。不要急着迁。**
