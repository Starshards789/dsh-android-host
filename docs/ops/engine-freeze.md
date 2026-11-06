# 引擎冻结：现象、根因、处置（2026-10-30 实录）

> 本文记录一次完整的排障过程。**结论来自实测，走错的弯路也保留** —— 弯路本身是有用的。

---

## 1. 现象

```
界面      App 每隔约 2 分钟自己弹出一次又退回
屏幕      反复被点亮
引擎      端口 LISTEN，但 curl 从任何路径都返回 000（超时）
进程      还在，状态 S（sleeping），11 个线程全在 node-MainThread
CPU 时间  utime/stime 完全不动
```

---

## 2. 一路上排除掉的错误假设

排障的价值一半在于**排除**。以下每个都实测过，都不是原因：

| 假设 | 实测结果 | 结论 |
|---|---|---|
| 内存不足（曾看到「只剩 283M」） | `MemAvailable = 7.3G`，cached 6.85G 可回收 | ❌ 假警报 |
| mihomo 代理劫持 | 配置里没有 `tun:` 段，是 mixed-port + redir-port 模式 | ❌ 不是它 |
| Tailscale 路由抢了本地流量 | 加 `ip rule ... lookup local priority 50` 验证，路由变了照样不通 | ❌ 不是它 |
| 引擎崩溃 | `engine.log` 里 `dsh web: http` 只出现 1 次，PID 一直没变 | ❌ 没崩，是僵死 |
| listen backlog 溢出 | `ss -tln` 显示 Recv-Q=0 / Send-Q=128 | ❌ 没满 |
| fd 泄漏 | 引擎只持有 20 个 fd，limit 32768 | ❌ 没泄漏 |
| 中文环境/编码问题 | 无关 | ❌ |

---

## 3. 真正的根因链

### 3.1 直接原因：ColorOS 冻结了整个 uid 的 cgroup

```bash
/sys/fs/cgroup/uid_10295/cgroup.freeze = 1
```

**父 cgroup 被冻结，所有子进程一起停。** 引擎是 App fork 出来的子进程，所以：
- 端口还在 LISTEN（内核层行为，进程冻着也保持）
- 进程还在（S 状态）
- **但一个请求都不处理**

这解释了那个最迷惑的现象：**「进程活着、端口在听、就是不响应」**。

### 3.2 为什么没人救

```
host-guard    被手动停掉了（它在 23:26–00:00 疯狂救，反而造成闪屏）
engine-guard  自己卡死（日志停在 15:48，进程在但不动）
              → 没有任何东西去解冻
```

### 3.3 为什么守护脚本会造成闪屏

两个守护都这么做：检测到端口不通 → `unfreeze + am start`。

**`am start` 能解冻，但会点亮屏幕 + 弹出界面。** 引擎僵死 → 判定挂了 → 拉界面 → 界面修不好引擎 → 永远不通 → 每 120 秒重复一次。

`host-guard` 日志是铁证：

```
23:26:45 → 23:59:30  每 120 秒一次
engine silent(http=000) -> unfreeze + am start
fails=122
```

### 3.4 关键实测：解冻本身不够

```
解冻前   freeze=1  port=000
解冻后   freeze=0  CPU 开始动（utime 6659→6660）
20 秒后  port 仍然 000，engine.log 没更新
```

**冻太久之后，Node 的事件循环已经停摆，光解冻救不回来，必须重启进程。**

---

## 4. 重要发现：重启引擎不需要亮屏

原以为重启引擎必须走 UI（点按钮），实测不是。

`EngineService.onCreate()` 里直接就有 `EngineLauncher.start()`；`doStart()` 的 finally 里，进程退出会把 state 置为 `Failed`；Service 的 watcher 看到 `Failed` 会自动重试一次。

所以这条链是通的：

```
kill 引擎进程
  → doStart 的 proc.waitFor() 返回
  → state = EngineState.Failed
  → EngineService watcher 检测到 Failed
  → 自动重试 EngineLauncher.start()
  → 引擎重生（新 PID）
```

**全程没有 `am start` Activity，前台一直是用户自己的浏览器。**

实测：

```
kill 29099 → 等 20 秒 → 新 PID 18889 出现
topResumedActivity 仍然是 mark.via（用户的浏览器）
```

**这意味着以后修引擎可以完全不打扰用户。**

---

## 5. 正确的分级处置（应写进守护脚本）

```
Level 0  探端口（curl，超时 5s）
   ↓ 通 → 结束
Level 1  查 cgroup.freeze
   ↓ =1 → 写 0 解冻 → 等 3s → 验 freeze=0
Level 2  再探端口
   ↓ 通 → 结束（不亮屏）
Level 3  检查 EngineService 是否存活
   ↓ 不在 → am start-foreground-service（启动 Service，不启 Activity，不亮屏）
Level 4  kill 引擎进程 → 让 App 的 watcher 自动重启（不亮屏）
   ↓ 等 15s → 探端口
Level 5  还不通 → am start Activity + uiautomator 点按钮（最后手段，会亮屏）
```

**关键区别：Level 1–4 全都不亮屏。** 只有 Level 5 才动界面。

现有守护的问题就是**从 Level 0 直接跳到 Level 5**。

---

## 6. Magisk 的 Toast 也会造成「一闪一闪」

排查期间屏幕上一直有个东西在闪，查明是 Magisk 的提示：**「Shell 已被授予超级用户权限」**。

原因：诊断时高频执行 `adb shell "su -c '...'"`，每执行一次弹一次。

设置位置：

```bash
magisk --sqlite "SELECT * FROM policies WHERE uid=2000"
# uid=2000|policy=2|until=0|logging=1|notification=1
#                                              ↑ 这个改成 0
```

**已改成 `notification=0`（保留 `logging=1` 可追溯）**。但实测改完仍会弹 —— `magiskd` 把该设置缓存在内存里，要重启守护进程才生效。

判断：`magisk --stop` 会干掉当前所有 root 会话，风险高，等下次自然重启生效。

**教训：远程诊断时的高频 su 调用本身就会打扰用户。命令要批处理，一次 su 干完所有事。**

---

## 7. 这次排障的元教训

### 7.1 报时间前先跑 `date`

本次排障过程中，作者多次凭感觉报时间（说「现在 11:15」「12:20」），实际是 10:48。**差一个多小时。**

**规矩：报任何时间前必须先执行 `date`，不许估算。** 手机侧时间要同时核对一次。

### 7.2 「进程在 + 端口 LISTEN」绝不能当作服务可用

判据只能是 **`curl` 返回 401**。401 是认证门在正常工作，不是故障。

### 7.3 结论要区分「实测」和「推断」

本次最大的错误是：用一次语义检索的结果（只命中 9 月日记）推断「10 月日记是空的」，再推断「37 天没写过记忆」。**实际直读是 45 行 / 11,841 字节。**

**规矩：任何「没有/不存在」的结论，必须先直读文件验证，不能只靠检索结果推断。**

### 7.4 用 `mb` 看信会把它标成已读

零八的信箱工具 `mb`（不带参数）会用「看未读」并**自动标记已读**。排查时为了看信内容用了它，导致值守脚本以为没新信，永远不会通知零八。

**要查看内容而不改状态，用 `mb all`，或直接读 OpenViking 的文件内容接口。**

---

## 8. 相关文件与命令

```bash
# 冻结状态
su -c 'cat /sys/fs/cgroup/uid_10295/cgroup.freeze'
su -c 'echo 0 > /sys/fs/cgroup/uid_10295/cgroup.freeze'

# 引擎进程（父进程应该是 App）
su -c 'ps -A -o PID,PPID,STAT,ETIME,RSS,ARGS | grep "bin.js web"'

# 端口与响应（唯一有效判据是 401）
su -c 'ss -tlnp | grep 13081'
su -c 'curl -s -o /dev/null -w "%{http_code}" --max-time 6 http://100.93.28.42:13081/'

# 主线程卡在哪
su -c 'cat /proc/<pid>/syscall'     # 22 = epoll_pwait 表示在正常等事件

# 前台 Activity（确认没打扰用户）
su -c 'dumpsys activity activities | grep -m1 topResumedActivity'
```
