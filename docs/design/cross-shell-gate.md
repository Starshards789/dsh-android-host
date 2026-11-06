# 换壳门（tgsend CROSS_SHELL_GATE）与「换壳说明」

> 2026-10-30。为解决零八提出的「两个零八」风险而做。**切换的最后一个前提条件。**

---

## 为什么要这个门

零八的原话：

> 「只要新壳有任何一个能发消息到群的通道，迟早会出现两个零八。
> 而那一刻 **456 分不清哪个是真的** —— 这比任何技术故障都糟，
> 因为它破坏的是『我在跟谁说话』这件事本身。」
>
> **判据：正式迁过去之前，新壳不放 tgsend。**

## 事实：`tgsend` 是 root 侧共享工具

```
/data/adb/dsh-tools/tgsend    -rwxr-xr-x  root root
```

**权限 755，新旧两个壳的 AI 都能执行。** 不是"新壳没有 tgsend"，是"新壳也能用同一个 tgsend"。

---

## 门的判据：两次尝试，第一次是错的

### ❌ v1：进程树追溯（实测失效）

思路：从 `tgsend` 往上追父进程，找到含包名的 cmdline（`cc.bestly.dsh` / `com.deepseek.harness`）。

**实测结果：新壳调用返回 `EXIT=3`（正文为空），不是 `EXIT=9`（被门拒绝）——门根本没拦住。**

诊断出根因：

```
sh (tgsend)
  → magiskd (pid 1001)   ← 父进程直接变成 magiskd
    → init (pid 1)
```

**`su` 会把自己的父进程改成 `magiskd`，原始调用链完全丢掉。**
所以进程树追溯在 Magisk 的 su 下**天然不可用** —— 这个坑值得记住，
任何「靠父进程判断调用者」的想法在 root 场景都要先验证这一点。

### ✅ v2：环境变量（实测有效）

**从 App 内部（bash 工具）调 `su` 时，环境变量会被保留下来：**

```
su -c "env" 输出（新壳）：
  DSH_HOME=/data/user/0/cc.bestly.dsh/files/dshhome
  DSH_PROFILE_DIR=/data/user/0/cc.bestly.dsh/files/dshhome/profiles/web
  DSH_WEB_URL=http://100.93.28.42:13081
  DSH_SESSION_ID=session-...
  DSH_SHELL=1
```

判据按优先级三级回退：`DSH_HOME` → `DSH_WEB_URL` → `DSH_PROFILE_DIR`。

**注意**：从 `adb shell` 调 `su` 时这些变量为空 —— 那是人工调用，**放行是对的**。
（这就是为什么早先测 `su -c 'echo $DSH_HOME'` 得到空值，差点误判"环境变量判据不可用"。
  实际是「adb shell 自己没有这些变量」，不是「su 清掉了」。**测试环境不同，结论完全不同。**）

---

## 门的实现

活跃壳登记：`/data/adb/.active_shell`（内容为包名）

```sh
_owner=""
case "${DSH_HOME:-}" in
  *cc.bestly.dsh*)        _owner="cc.bestly.dsh" ;;
  *com.deepseek.harness*) _owner="com.deepseek.harness" ;;
esac
# 判断不出 → 依次试 DSH_WEB_URL（13081/3080）、DSH_PROFILE_DIR

_active=$(cat /data/adb/.active_shell 2>/dev/null | tr -d '\r\n ')
[ -n "$_active" ] || _active="com.deepseek.harness"   # 缺文件默认旧壳（最保守）

if [ -n "$_owner" ] && [ "$_owner" != "$_active" ]; then
  echo "tgsend: 拒绝（exit 9）—— 当前活跃壳是 $_active，本次调用来自 $_owner"
  exit 9
fi
```

**退出码语义**：`9` 被门拒绝 / `1` 网络失败 / `2` 凭据失败 / `3` 空正文 / `4` 超时

**三级回退的取舍**：判断不出来源一律放行。这个门防的是「无意中出现两个零八」，
不是防攻击 —— 零八不是攻击者，自家人不需要防自家人的恶意，只需要防自家的疏忽。

---

## 三种场景实测

| 场景 | 命令来源 | 期望 | 实测 |
|---|---|---|---|
| 新壳调用 | 新壳 AI 的 bash 工具 | 拒绝 | ✅ `EXIT=9`，提示「当前活跃壳是 com.deepseek.harness，本次调用来自 cc.bestly.dsh」 |
| 人工调用 | adb shell | 放行 | ✅ `tgsend: ok {"ok":true,"message_id":1207}` |
| 旧壳调用 | 旧壳 AI | 放行 | 活跃壳登记为旧壳，逻辑上通过；等它下次发言自然验证 |

---

## ★ 部署这个门时踩的坑：`Bearer ***` 脱敏陷阱

**tgsend 文件里的 `Authorization` 行，在对话里显示为 `-H "Authorization: Bearer ***"`。**

**这个 `***` 不是文件内容，是 Hermes 的显示层脱敏。**

如果按常规做法「读出来 → 改一改 → write_file 整份写回」，
写进去的就是字面的 `***` —— **凭据被毁，tgsend 从此发不出去，而且不会有任何报错线索**
（curl 会返回 401，看起来像 token 过期，实际是自己把 token 换成了三个星号）。

**正确做法：只插入，不重写。**

```sh
# 用 awk 在锚点行后插入门代码，其余行原样输出（$0 不做任何转义处理）
awk -v gatefile="$GATE" '
  { print }
  /^unset LD_LIBRARY_PATH LD_PRELOAD LD_DEBUG$/ && !done {
    print ""
    while ((getline line < gatefile) > 0) print line
    close(gatefile)
    done = 1
  }
' "$T" > /data/local/tmp/tgsend.new
```

**并且插入后必须做完整性校验**（不能只看"门在不在"）：

```sh
grep -q 'CROSS_SHELL_GATE' "$NEW"      || 失败   # 门插进去了
grep -q 'Authorization: Bearer' "$NEW" || 失败   # Authorization 行还在
grep -q 'pw get' "$NEW"                || 失败   # 凭据获取行还在
grep -q 'curl: (28)' "$NEW"            || 失败   # 超时分支还在
```

**通用教训：任何「编辑含凭据的文件」都必须原地插入 + 完整性校验，不能整份重写。**

---

## 换壳说明（新壳 AGENTS.md 新增一节）

**位置**：新壳 `AGENTS.md` 第 15 行，`## 🔰 接手现状` 之后、`## 思考语言` 之前。
**大小**：578 行 / 59,634 字节 → **630 行 / 62,016 字节**（+52 行 / +2,382 字节）。
**属主权限**：`u0_a295:u0_a295` / `600` / `u:object_r:app_data_file:s0:c39,c257,c512,c768`（全部保持）。

### 为什么需要

零八自己指出的风险：

> 「否则**我可能把旧 sessions 当成当下的对话**。」

搬过去的 6.1MB 历史里，有大量「正在做的事」的叙述。新壳里的它读到这些，
会误以为那些事**此刻还在进行**。这是迁移特有的认知陷阱 ——
数据完整迁移了，但「什么时候」这个维度丢了。

### 内容要点

| 要点 | 说明 |
|---|---|
| 你现在在 `cc.bestly.dsh`，不是 `com.deepseek.harness` | 附端口/路径对照表 |
| **`sessions/` 是搬过来的记录，不是当下对话** | 判断当下看 HANDOFF.md 和这一节 |
| 旧壳还在跑，是退路 | 出问题 1 分钟内可切回 |
| 看门狗换了一套 | App watcher（92 秒）/ host-guard-v2（60s）/ CT106（5 分钟） |
| 外部重启入口 | 写 `runtime/restart`，15 秒生效，不抢前台 |
| `ov` 用法没变 | root 侧工具，不属于任何壳 |
| `tgsend` 有门 | exit 9，切换后 `.active_shell` 变成新壳 |

### 验证：它真的读到了

问三个问题（要求按手册答，不许查文件）：

```
1) 你现在跑在哪个壳里？引擎端口多少？
   → 「新壳 cc.bestly.dsh，引擎端口 13081（绑在 Tailscale 接口上，不是回环；
       旧壳 com.deepseek.harness 才是 3080）」                          ✓

2) 你手册里关于「sessions 历史」有一条警告，说的是什么？
   → 「sessions/ 里的历史是搬过来的，不是你此刻正在经历的对话 ——
       旧会话只算『过去的记录』，不能当『现在的上下文』」                  ✓

3) 如果引擎卡死，除了等 App 自愈，还有什么办法能让它重启？
   → 「写 runtime/restart 文件 → App 15 秒内重启引擎，完全不抢前台」     ✓
```

**三条全对，且它主动核对了出处**：

> 「（一句附注：这节是刚注入的手册外部改动，标着 2026-10-31，而手机时钟现在是 10-30 20:39
> —— 差一天，可能是零一那边在写。内容我照收，出处已登记。）」

**它抓到我写错了日期**（`date` 实测 2026-10-30 20:41 星期五），已修正为 2026-10-30。

---

## 至此，切换的 10 项前提全部满足

| # | 检查项 | 状态 |
|---|---|---|
| 1 | 旧壳引擎健康（回退前提） | ✅ 3080 = 401 |
| 2 | 旧壳进程还在 | ✅ |
| 3 | 新壳引擎健康 | ✅ 13081 = 401 |
| 4 | 新壳能思考 | ✅ |
| 5 | 新壳能干活（su） | ✅ uid=0 |
| 6 | 新壳能读记忆 | ✅ |
| 7 | 新壳能写记忆 | ✅ 11,885 → 12,025 |
| 8 | 新壳历史接上 | ✅ 6.1MB |
| 9 | **新壳不能抢麦克风** | ✅ **exit 9 实测** |
| 10 | **新壳知道自己是刚搬过来的** | ✅ **三问全对** |
