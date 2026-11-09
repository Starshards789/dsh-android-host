# 代理 UI 方案（dsh 壳内嵌）

> **状态**：设计稿（未实施，等 456 批）
> **日期**：2026-11-09
> **作者**：零一 + 零八
> **需求提出**：456

---

## 0. 需求

### 456 原话

> 我想要的功能是给零八搜索和下载，还有其他APP可以单独开关，最好是可视化我自己想给那个app用的时候可以开关和添加

> ui要有到任何输出代理配置文件，代理的模式，其他app的添加和开关，还要显示节点和选择

> 在dsh壳的跟随系统旁边加代理按键

> 代理按键是进入代理ui的，不是代理开关

### 技术翻译

| 需求 | 技术实现 |
|---|---|
| 零八能搜索和下载 | 代理对她进程生效 |
| 其他 App 单独开关 | 按 UID 分流 |
| 可视化开关和添加 | UI |
| 导入/输出配置文件 | YAML 导入导出 |
| 代理模式 | 规则 / 全局 / 直连 |
| 节点显示和选择 | 节点列表 + 切换 |

---

## 1. 约束

| 约束 | 说明 |
|---|---|
| **单 VPN 槽位** | Tailscale 占用 → TUN 不可用 → 只能用 iptables |
| **root 可用** | Magisk |
| **mihomo 已验证** | 之前 `px` 方案跑过 |
| **代理独立于 App** | App 关了代理在（否则零八断网） |

---

## 2. 架构

```
┌─────────────────────────────────────────────┐
│  dsh 壳（App）                               │
│  ┌───────────────────────────────────────┐  │
│  │ MainActivity                          │  │
│  │  [跟随系统] [代理] ← 新增按钮（入口）  │  │
│  └───────────────────────────────────────┘  │
│  ┌───────────────────────────────────────┐  │
│  │ ProxyActivity（新增）                  │  │
│  │  WebView 加载本地 HTML                │  │
│  └───────────────────────────────────────┘  │
└──────────────────┬──────────────────────────┘
                   │ HTTP（127.0.0.1:9090）
┌──────────────────▼──────────────────────────┐
│  mihomo（root 进程，独立于 App）             │
│  · HTTP 代理端口（默认 7890）                │
│  · redir 透明代理端口（默认 7892）           │
│  · 控制 API（默认 9090）                     │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│  iptables（按 UID 分流）                     │
│  · 选中的 App → redir 端口                   │
│  · 其他 → 直连                               │
└─────────────────────────────────────────────┘

启动：service.d 脚本 → 等网络就绪 → 起 mihomo
```

**注意**：端口不写死，见 §4.1 的「怎么查」。

---

## 3. 组件

### 3.1 mihomo（内核）

| 项 | 怎么查 |
|---|---|
| 版本 | `mihomo -v`（当前 v1.19.31，仅作参照） |
| 路径 | `/data/adb/proxy/mihomo` |
| 配置 | `/data/adb/proxy/config.yaml`（600） |
| 控制 API | 配置里的 `external-controller` |
| secret | `/data/adb/proxy/.secret` |
| 许可 | GPL-3.0（独立二进制，不传染） |

### 3.2 proxy-app（CLI）

**路径**：`/data/adb/dsh-tools/proxy-app`

**子命令**：

```sh
proxy-app on <uid|包名...>   # 加分流规则（带注释 com.dsh.proxy）
proxy-app off                # 精确删除（按注释匹配）
proxy-app status             # 列出当前规则
proxy-app list               # 列出已安装 App + 当前分流状态
```

**关键约束**：
- **本地脚本**：断网也能跑（`sh` 不需要网络）
- **不在 service.d**：防重启时把坏规则加回来
- **规则带注释**：`com.dsh.proxy`，可按注释精确删除
- **UID 动态查**：`pm list packages -U`，不写死

### 3.3 UI（ProxyActivity）

**入口**：`MainActivity` 右上角 `[跟随系统 | 代理]`

**页面**（WebView 加载本地 HTML）：
- 状态（mihomo 跑没跑 / 当前节点 / 出口 IP）
- 启停
- 模式（规则 / 全局 / 直连）
- 节点列表 + 切换 + 测速
- App 分流（列表 + 开关）
- 配置（导入 / 导出 / 订阅）

**关键约束**：
- **独立 WebView**（不共用对话页，避免切走丢状态）
- **状态从 API 拿**（不在 HTML 里硬编码）
- **UI 只是控制面板**（不管理进程）

---

## 4. 接口

### 4.1 mihomo API

| 功能 | 方法 | 路径 |
|---|---|---|
| 版本 | GET | `/version` |
| 配置 | GET / PATCH | `/configs` |
| 节点列表 | GET | `/proxies` |
| 节点切换 | PUT | `/proxies/<组名>` |
| 延迟测试 | GET | `/proxies/<组名>/delay` |
| 流量 | GET | `/traffic` |

**认证**：`Authorization: Bearer <secret>`（secret 见 §3.1）

**端口怎么查**：读 `/data/adb/proxy/config.yaml` 的 `external-controller` 字段，不要假设 9090。

### 4.2 proxy-app

| 命令 | 作用 |
|---|---|
| `on <uid...>` | 加规则 |
| `off` | 删规则 |
| `status` | 列出规则 |
| `list` | App 列表 + 状态 |

---

## 5. iptables 规则

```sh
# 白名单：不劫持（必须在前）
iptables -t nat -A OUTPUT -d 127.0.0.0/8 -j RETURN
iptables -t nat -A OUTPUT -d 100.64.0.0/10 -j RETURN   # Tailscale 段

# 加分流（示例：零八，uid 用 pm list packages -U 查）
iptables -t nat -A OUTPUT -m owner --uid-owner <UID> \
  -p tcp -m comment --comment "com.dsh.proxy" \
  -j REDIRECT --to-ports <redir端口>

# 删除（按注释匹配）
# 由 proxy-app off 实现：遍历 -m comment --comment "com.dsh.proxy" 的规则，逐条 -D
```

**四个坑**：

1. **`nat OUTPUT`**（不是 `filter`，Android 10+）
2. **UID 动态查**（App 重装会变）
3. **不劫持 `127.0.0.1` / Tailscale 段**（否则死循环）
4. **重启即失**（iptables 规则不在磁盘 → `service.d` 重建）

### 5.1 DNS 与域名识别（2026-11-09 实测修正）

#### 原方案（已证伪）：iptables 劫持 DNS

原设计想让 App 的 DNS 查询也走 mihomo（`-p udp --dport 53 -j REDIRECT --to-ports <dns端口>`），
再配合 `enhanced-mode: fake-ip` 返回假 IP。

**实测：这条规则 `pkts=0`，一个包都没拦到。**

原因：**Android 10+ 的 DNS 由系统 `netd` 的 DnsResolver 代发**，不是 App 进程自己发包。
`iptables -m owner --uid-owner <App UID>` 只能匹配「该进程自己发出的包」，
netd 发的包 UID 属于 netd → 规则永远不命中。

**结论：在 Android 10+ 上，按 UID 劫持 DNS 这条路走不通。**

#### 正确方案：sniffer（TLS SNI / HTTP Host 嗅探）

不劫持 DNS，改为让 mihomo 从协议握手包里读出域名：

```yaml
sniffer:
  enable: true
  force-dns-mapping: true     # DNS 映射失败时也尝试嗅探
  parse-pure-ip: true         # 目标是纯 IP 也嗅探
  override-destination: true  # 用嗅探出的域名覆盖目标
  sniff:
    TLS:
      ports: [443, 8443]
    HTTP:
      ports: [80, 8080-8880]
    QUIC:
      ports: [443, 8443]
```

**为什么这样能工作**：

1. App 用真实（可能被污染的）IP 连出去
2. iptables 把 TCP 拦到 mihomo 的 redir 端口
3. mihomo 从 TLS ClientHello 的 SNI 里读出真实域名
4. 按域名匹配规则 → 交给代理服务器
5. 代理服务器自己解析域名（不受本机 DNS 污染影响）

#### 验证方法（决定性测试）

故意连一个错误的 IP，靠 SNI 救回来：

```sh
# 1.2.3.4 是假 IP，正常不可能通
curl --resolve www.google.com:443:1.2.3.4 https://www.google.com
# 期望 200；mihomo 日志应出现 --> www.google.com:443
```

#### fake-ip 还要不要

保留 `enhanced-mode: fake-ip` 无害（mihomo 内部解析器仍用它），
但**不要指望 App 能拿到 `198.18.x.x`** —— App 的 DNS 不走 mihomo。

> 实测：直接向 `127.0.0.1:1053` 手写 DNS 包，mihomo 返回 `198.18.0.4`（fake-ip 本身是正常的）；
> 但 App 的查询根本不经过这个端口。

⚠️ **`dns.fallback` 仍然不能加**：触发 geodata 自动下载 → 启动 fatal（2026-09 踩过，记在 `/root/selfproxy-2026/README.md`）。

### 5.1b 节点可用性（2026-11-09 实测）

`HK4-尾网` / `US1-尾网` 走 Tailscale（`100.x.x.x`），当前**不通**：

- `ping 100.106.130.58` → **100% packet loss**
- mihomo 延迟测试 → **Timeout**

**影响**：`fallback` 组默认按顺序取第一个，选了尾网 → 全部流量走死路（`dial tcp 100.106.130.58:443: i/o timeout`）。

**对策**：组顺序改**公网优先**：

```yaml
proxy-groups:
  - name: HK4
    proxies: ["HK4-公网", "HK4-尾网"]
  - name: US1
    proxies: ["US1-公网", "US1-尾网", "US1-Hy2"]
```

**待办**：Tailscale 到 HK4/US1 的连通性单独排查（独立问题，不影响公网节点可用性）。

### 5.2 检查已有规则（必须）

`proxy-app on` **之前**先跑 `proxy-app status`，检查有没有冲突：

- 别的 `REDIRECT`（例如 `31-gms-proxy.sh` 留下的 GMS 分流）
- 同一 UID 的重复规则

**有冲突时**：先 `proxy-app off`（只删 `com.dsh.proxy` 注释的），别人的规则**不要动**（需单独确认归属）。

---

### 5.3 删除类操作的五条纪律（2026-11-09 实测踩坑）

阶段 3 调试中连续踩了四个坑，全是「一句话写错，作用域失控」——和 2026-11-07 那次
`chmod 644 $P/*` 因变量没展开变成 `chmod 644 /*` 是同一类错误模式。

| # | 错误写法 | 后果 | 正确写法 |
|---|---|---|---|
| 1 | `off) cmd_off ;;` | 漏 `shift` → `off <uid>` 走进「全清」分支，删光所有 App 的规则 | `off) shift; cmd_off "$@" ;;` |
| 2 | `iptables -C ... -m comment ... \|\| -A ...` | `-C` 对 `-m comment` 匹配不可靠 → 重复叠加，实测 4 条变 8 条 | `purge_uid_rules` 先删再 `-A` |
| 3 | 一次算好行号 `sort -rn \| while read n; do -D $n` | 删一条后行号错位，实测 8 条只删掉 2 条 | `while :; do n=$(... \| tail -1); -D $n; done` 每次重算 |
| 4 | UI 列表按「已开启优先」动态排序 | 开关一变列表就重排 → 用户点第二下时点到别的行（实测把 Tailscale 误开了分流） | 固定排序（包名），状态变化不重排 |
| 5 | `off` 无参数立即全清 | 手滑/脚本出错就全删 | 无参数 = dry-run，打印「将删除 N 条」+ 列表，必须 `--yes` 才执行 |

**通用规则**：

1. **删除前先 dry-run** —— 批量/无参数删除默认只打印影响面，`--yes` 才动手
2. **作用域显式** —— 有参数只删指定对象；无参数 = 全清 = 高危，必须二次确认
3. **幂等靠「先删后加」**，不要依赖 `-C` 的匹配语义
4. **批量删除不要缓存行号**，每轮重算
5. **UI 列表顺序必须稳定**，别按状态动态排序

---

### 5.4 GEOSITE / GEOIP 规则集（2026-11-09 新增）

**核心事实：`GEOSITE` / `GEOIP` 不是 mihomo 自带的，需要两个数据文件。**

| 文件 | 大小 | 用途 | 放哪 |
|---|---|---|---|
| `geosite.dat` | 4.1 MB | 域名库（`GEOSITE`） | `/data/adb/proxy/` |
| `geoip.metadb` | 8.0 MB | IP 库（`GEOIP`） | `/data/adb/proxy/` |

**为什么重要**：`GEOSITE,cn` 覆盖 **110,177 条**国内域名，替代手写关键词列表（B站/知乎/微博/京东 这类非 `.cn` 的国内站也能直连）。

#### 坑 1：手机下不了

mihomo 启动时如果找不到这两个文件，**会自动从 GitHub 下载** —— 手机直连 GitHub Release **超时**（`dial tcp 20.205.243.166:443: connect: connection timed out`），**启动直接失败**。

**正确做法**：

```bash
# 1. 在有网络的机器（CT106）下载
curl -L -o geosite.dat  https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat
curl -L -o geoip.metadb https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb

# 2. push 到手机
adb push geosite.dat  /data/local/tmp/
adb push geoip.metadb /data/local/tmp/
adb shell "/debug_ramdisk/magisk su -c 'cp /data/local/tmp/geosite.dat /data/local/tmp/geoip.metadb /data/adb/proxy/ && chmod 644 /data/adb/proxy/geosite.dat /data/adb/proxy/geoip.metadb'"

# 3. 测试配置（必须通过再重启）
adb shell "/debug_ramdisk/magisk su -c '/data/adb/proxy/mihomo -t -d /data/adb/proxy'"
```

**⚠️ 下载后必须校验 sha256** —— 这两个是**二进制数据文件**，下错/损坏**不一定会报错**（可能加载一部分后崩溃，或悄悄走错规则），**校验比调试便宜**：

```bash
sha256sum geosite.dat geoip.metadb
# 和 Release 页 / 下载来源给出的 sha256 对比
```

**2026-11-09 实测值**（对应 `latest` release）：

| 文件 | 大小 | sha256 |
|---|---|---|
| `geosite.dat` | 4,355,962 | `85073a00a3c3eb80ac76b97df2f97f7dab00cb36c8b5e7ed2354cbd016f8b088` |
| `geoip.metadb` | 8,406,197 | `f0508298d0295ce4ae0d6d185ba24605b6ec1ef9c42409dec3271e6525b5486e` |

**注意**：`latest` 会更新，**sha256 也会变** —— 校验的是「**下载源 vs 手机**」一致性（或与 Release 页对比），不是和这里的历史值对比。

**注意**：`geoip.dat`（v2ray 格式）**不是** mihomo 用的 —— **要 `geoip.metadb`**（MMDB 格式）。下错了会继续报 `Can't find MMDB`。

#### 坑 2：更新 mihomo 时要一起换

`geosite.dat` / `geoip.metadb` 是**静态文件**，不会自动更新：

- **换 mihomo 版本** → 保留旧文件通常兼容
- **但规则库过期**（新域名没覆盖）→ 要手动更新
- **更新方式**：重复上面的下载 + push + 重启

#### 坑 3：备份清单

**重刷 / 迁移时必须一起恢复**：

```
/data/adb/proxy/config.yaml
/data/adb/proxy/geosite.dat
/data/adb/proxy/geoip.metadb
/data/adb/proxy/.secret
```

#### 规则顺序（重要）

```
内网直连 → 特殊直连(deepseek/icloud) → 特殊代理(github/openai) → GEOSITE,cn → GEOIP,CN → MATCH
```

**`GEOSITE,cn` 必须放在「特殊代理」之后** —— 否则某个域名若同时在「中国域名库」和「走代理」列表里，会被直连（错）。

#### 实测数据（2026-11-09）

| 站点 | 匹配 | 走向 | 耗时 |
|---|---|---|---|
| bilibili.com | `GeoSite(cn)` | DIRECT | 0.13s |
| zhihu.com | `GeoSite(cn)` | DIRECT | 0.17s |
| google.com | `Match` | PROXY[HK4] | 1.9s |
| github.com | `DomainSuffix(github.com)` | US1 | 2.4s |
| api.deepseek.com | `DomainSuffix(deepseek.com)` | DIRECT | 0.17s |
| icloud.com | `DomainSuffix(icloud.com)` | DIRECT | 0.15s |

**加载时间**：`GEOSITE,cn` 110,177 条 → **312ms**（`memconservative` 模式）

**来源标注**：规则库来自 [MetaCubeX/meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat)（mihomo 官方维护）；`GEOSITE,cn` 分类本源自 [v2fly/domain-list-community](https://github.com/v2fly/domain-list-community)。

---

## 6. UI 页面结构

```
┌─────────────────────────────┐
│ ← 代理                       │
├─────────────────────────────┤
│ 状态: ● 运行中  [停止]        │
│ 出口: <当前出口 IP 与地区>    │
│ 模式: [规则] [全局] [直连]    │
├─────────────────────────────┤
│ 节点:                        │
│  ● <节点1>                   │
│  ○ <节点2>                   │
│  ○ <节点3>                   │
│  [测速] [自动选择]            │
├─────────────────────────────┤
│ App 分流:                    │
│ ☑ 零八 (cc.bestly.dsh)       │
│ ☐ <其它已安装 App>           │
│ [+ 添加 App]                 │
├─────────────────────────────┤
│ 配置:                        │
│ [导入 YAML] [导出] [订阅]     │
└─────────────────────────────┘
```

**节点、出口 IP、App 列表全部来自 API / 系统查询，页面不写死。**

---

## 7. 三阶段

### 阶段 1：状态 + 启停（不碰 iptables）

**做什么**：
- `MainActivity` 加按钮
- `ProxyActivity` + WebView
- 状态显示（`GET /version`）
- 启停（`kill` / service.d 启动）

**验收**：
- 按钮能进页面
- 状态显示正确
- 启停生效

**回滚**：
- **回退 APK 到上一版**：`adb install -d <旧版 APK>`（`-d` 允许降级）
  - ⚠️ **绝不能卸载**：卸载会清掉 App 数据（对话记录、配置、插件、`dshhome`），等于毁掉整个 DSH
- 或 `kill mihomo` + 删 service.d 脚本

### 阶段 2：节点切换 + 测速

**做什么**：
- 节点列表（`GET /proxies`）
- 节点切换（`PUT /proxies/<组>`）
- 测速（`GET /proxies/<组>/delay`）

**验收**：
- 节点列表正确
- 切换生效（出口 IP 变化）
- 测速有结果

**回滚**：
- 配置回滚（`config.yaml.bak`）

### 阶段 3：App 分流（iptables，最危险）

**做什么**：
- `proxy-app` 脚本
- iptables 规则
- UI 的 App 列表 + 开关

**验收**：
- 选中 App 走代理
- 未选中 App 直连
- **Tailscale 不受影响**（`curl http://<tailscale IP>:<引擎端口>/` → 401，不是 000/超时）
- `proxy-app off` 恢复直连

**回滚**：
- `proxy-app off`（一条命令）
- `iptables -t nat -F`（兜底）

---

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| iptables 断网 | `proxy-app off`（本地脚本，断网也能跑） |
| UID 变化 | `pm list packages -U` 动态查 |
| 重启失规则 | service.d 重建 |
| Tailscale 被劫持 | 白名单 `RETURN` + 验收 401 |
| mihomo 崩溃 | service.d 拉起 + 健康检查 |
| 配置泄露 | 私钥 / UUID / SNI 不内置，只留模板 |
| WebView 冲突 | 独立 Activity |
| 版本过时 | 文档写「怎么查」，不写死 |

---

## 9. 分工

| 谁 | 做什么 |
|---|---|
| **零一** | Kotlin（按钮 + Activity）、CI 构建、文档 |
| **零八** | `proxy-app` 脚本、iptables、设备侧验收 |

---

## 10. 验收标准

| 项 | 标准 |
|---|---|
| 按钮 | 能进代理页（不是开关） |
| 状态 | 显示运行状态、当前节点、出口 IP |
| 模式 | 规则 / 全局 / 直连切换生效 |
| 节点 | 列表 + 切换 + 测速 |
| App 分流 | 选中走代理、未选中直连 |
| **Tailscale** | 不受影响（401） |
| **回滚** | `proxy-app off` 一条命令 |

---

**状态：设计稿，等 456 批**
