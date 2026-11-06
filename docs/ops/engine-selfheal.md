# 引擎自愈修复记录（v0.8.1 → v0.8.4，2026-10-30）

> 这一轮是**纯实测驱动**的：改一版、装机、真杀引擎、看日志、发现新问题、再改。
> 四个版本，每一版都是被上一版暴露的问题逼出来的。全文保留过程，因为**过程比结论更有用**。

---

## 0. 起因

2026-10-30 18:42，新壳引擎被 ColorOS 冻结太久后僵死。三开关开好（`freeze` 1→0）之后
`kill -9` 引擎想让 App watcher 自愈 —— **没自愈，从此卡死**：

```
18:42:28  kill -9 引擎 18889
18:43:31  引擎没起来，端口 000
          App 主进程 19120 还在
          前台服务 isForeground=true
          runtime/pid 还是 00:27 的旧值   ← App 根本没尝试启动新引擎
```

于是有了 456 批准的「C 方案」：修 bug、重新构建、装新版本。

---

## 1. 四个真 bug（都不是推演出来的）

### bug ① `onStartCommand` 只认 Idle，不认 Failed

```kotlin
// 修之前
if (EngineLauncher.state.value is EngineState.Idle) {
    EngineLauncher.start(this, scope)
}
```

后果链：

```
kill 引擎 → proc.waitFor() 返回 → 状态变 Failed
         → watcher 收到 → 等 15 秒 → 重试一次 start()
         → 这次也失败 → 状态还是 Failed
         → watcher 用完它唯一的一次重试
         → 之后谁再调 onStartCommand 都因为「不是 Idle」而什么都不做
         → 永远卡在 Failed
```

**这是「看门狗只能救一次」的结构性缺陷。** 08 在 2026-08 的旧壳审查里提过同一类问题（看门狗悖论），当时没修到新壳上。

修法：

```kotlin
when (EngineLauncher.state.value) {
    is EngineState.Idle, is EngineState.Failed -> { failCount = 0; EngineLauncher.start(...) }
    else -> hostLog("跳过")
}
```

### bug ② watcher 只重试一次

```kotlin
// 修之前：delay 之后检查一次，失败就再也不试
delay(RETRY_DELAY_MS)
if (EngineLauncher.state.value is EngineState.Failed) { EngineLauncher.start(...) }
```

修法：指数退避 + 成功后清零。

```
第 1 次 15s → 第 2 次 30s → 第 3 次 60s → 第 4 次 120s → 之后封顶 300s
```

封顶是为了「坏环境里不疯狂重试烧电」。

### bug ③ `appendLog` 只写内存，不落文件

```kotlin
// 修之前
private fun appendLog(line: String) {
    _log.value = (_log.value + line).takeLast(400)   // 只在内存 LiveData 里
}
```

**后果：从 adb shell 侧完全看不到 App 内部发生了什么。** 当天因此把「watcher 到底有没有动作」判断错一次，白绕一圈。

修法：同时追加到 `runtime/engine-host.log`（EngineLauncher）和 `runtime/host.log`（EngineService），超过 256KB 截半。

**这个修复的直接回报：后面三个 bug 全靠它才看得见。**

### bug ④ 旧协程的 `finally` 把新引擎判死（两处竞态）

**第一层**（v0.8.1 → v0.8.2）：`restart()` 置空 `process` 后杀旧引擎、起新的；
旧 `doStart` 的 `finally` 晚一步跑，看到状态是 `Starting` 就无条件改成 `Failed`，
还把 `process` 置空 —— 新引擎从此无人接管。

```kotlin
// 修法
if (process === proc) { ...善后... } else { appendLog("（旧引擎的退出，当前已有新实例，忽略）") }
```

**第二层**（v0.8.2 → v0.8.3 → v0.8.4）：修完第一层后失败信息变成
`启动异常: read interrupted` —— 因为 `restart()` 里 `readerJob?.cancel()` 取消了旧协程，
旧协程正在阻塞读 stdout，被取消时抛异常，它自己的 `catch` 把它当启动失败。

先加 `generation` 启动代号（v0.8.3），但**还是报**：

```
v0.8.3 实测：
  19:13:27  强制重启
  19:13:30  state -> 启动失败 · 启动异常: read interrupted
  19:14:09  === 就绪: port=13081 probe=401 ===   ← 引擎其实成功了
```

根因是**递增时机**：`val myGen = ++generation` 写在 `start()` 里，
而旧协程在 `cancel()` 之后、`++` 之前就抢先跑了 `catch`，那时 `generation` 还是它自己的号，
自认为是最新启动，于是把自己判成失败。

**v0.8.4 修法：`restart()` 和 `stop()` 开头就 `generation++`，把在飞的旧启动一次性作废。**

---

## 2. 三处新能力（其中两条来自 08 的审阅意见）

| 能力 | 作用 | 实现 |
|---|---|---|
| **外部重启入口** | root 侧 `create runtime/restart` 就能救引擎，**不用 `am start` 抢前台** | Service 每 15s 轮询该文件，读到即删（一次性信号）→ `EngineLauncher.restart()` |
| **健康检查双条件** | 状态 `Running` ≠ 服务可用 | 每 60s 用裸 Socket 真探端口，探不到就重启 |
| **心跳文件** | 外部能判断「看护本身还活着吗」（看门狗悖论） | 每 30s 写 `runtime/heartbeat`（时间戳 + 当前状态），外部超过 3 分钟没更新即视为看护已死 |

健康检查这条直接来自 08 在旧壳审出的 bug：`ps | grep` 会匹配到自己 → 永远判「引擎在跑」。

---

## 3. 实测验收（全部真机，可复现）

### ① 崩溃自愈（kill -9）

```
19:01:34  kill -9 引擎 26574
19:01:34  state -> 启动失败
19:01:34  失败(第 1 次)，15s 后重试        ← 退避机制
19:02:08  开始第 1 次重试
19:02:51  state -> 运行中 · 端口 13081
19:03:00  新 PID 27623，本机 curl = 401
```

**耗时 1 分 26 秒，零人工干预。**

### ② 外部重启入口

```
19:17:47  === 强制重启（外部请求，gen=2）===
19:17:48  （这是旧引擎的退出，当前已有新实例，忽略）
19:17:48  旧启动异常（gen=1 已被 gen=2 取代，忽略）: read interrupted
19:17:48  绑定地址 100.93.28.42
19:18:21  === 就绪: port=13081 probe=401 ===
```

**34 秒，一次过，不再走「失败 → 退避」路径。**
（对比 v0.8.3：42 秒，中间经过一次误判失败）

### ③ 健康检查抓僵死（kill -STOP）

```
19:18:56  健康检查失败: 端口 13081 无应答（进程在但服务僵死） → 重启引擎
19:19:02  state -> 未启动
19:19:02  state -> 启动中…
19:19:46  state -> 运行中 · 端口 13082
```

**22 秒识别。** `kill -STOP` 造成的正是最阴的那种故障：进程在、端口 LISTEN、但一个请求都不处理。

---

## 4. ★ 新发现：端口会漂移

健康检查重启时，如果僵死进程还占着原端口（`kill -STOP` 的进程不会释放 socket），
`probePorts` 会避让，引擎改绑下一个端口（实测 13081 → 13082）。

**这是正确行为（宁可换端口也不要起不来），但调用方必须知道：**

- ✅ **读 `runtime/port` 文件**（这是唯一权威来源，重启后自动更新）
- ❌ **不要硬编码 13081**

2026-10-30 排查结论：本机（`tg8-relay`、Hermes scripts）和手机侧（`/data/adb/dsh-tools/*`、
`/data/adb/service.d/*.sh`）**都没有硬编码 13081**，全部走 `runtime/port`。
而且 `tg8-relay` 连的是旧壳（3080），新壳目前的端口漂移对外无影响。

**但迁移之后要注意这条。**

---

## 5. 还没解决的

| # | 问题 | 状态 |
|---|---|---|
| 1 | **状态机正在变复杂**（状态标志 + 代号 + 退避互相覆盖） | 值得重构：把「启动」做成明确的 Job 生命周期，而不是靠标志互相覆盖。**已向 456 提议，未批准不动。** |
| 2 | ColorOS 冻结期间 App 自己做不了任何事 | 只能靠 root 侧兜底（`host-guard`）+ 已开的三个开关。08 建议「冻超 5 分钟要重启引擎」，这条在 root 侧做 |
| 3 | `heartbeat` 目前只有 App 在写，还没有外部消费者 | 待接：CT106 侧定时读，超时报警 |
