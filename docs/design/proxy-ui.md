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
- 卸载 App（`adb uninstall`）→ 回到旧版本
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
