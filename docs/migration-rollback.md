# 迁移切换 · 回退手册（2026-10-30）

> 零八的判据：**「任何声称『一分钟能恢复』的方案，都要能当场说出那几条命令。」**
> 这份文件就是那几条命令。切换前必读，切换时照抄。

---

## 切换动作（只有一处）

TG 中继 `tg8-relay` 的目标从旧壳改到新壳。

```
旧壳   com.deepseek.harness   127.0.0.1:3080    uid 10284   category c28
新壳   cc.bestly.dsh          100.93.28.42:13081 uid 10295   category c39
```

中继配置：`/root/tg8-relay/config.json` 里与引擎地址相关的那几项。

---

## 回退命令（切回旧壳，30 秒内完成）

### 第 1 步：改回中继配置

```bash
cd /root/tg8-relay
cp config.json config.json.bak-$(date +%Y%m%d-%H%M%S)     # 先备份当前（新壳）配置
# 把 config.json 里的引擎地址/端口改回旧壳：127.0.0.1:3080  ← 从备份文件恢复也行
```

### ★ 第 1.5 步：把 cookie 也换回旧壳的（切换时踩出来的坑，缺这步照样 401）

```bash
# 中继用 cookie jar 认证，jar 里装的是「当前壳的 token」
# 只改地址不换 cookie → 旧壳收到新壳的 token → unauthorized
ls /root/.hermes/state/8t-cookie.jar.bak-oldshell-*      # 切换时自动备份的那份
cp /root/.hermes/state/8t-cookie.jar.bak-oldshell-*  /root/.hermes/state/8t-cookie.jar
chmod 600 /root/.hermes/state/8t-cookie.jar
```

**验证 cookie 归属**（能报错的验法）：

```bash
adb -s 100.93.28.42:5555 shell "su -c 'curl -s -b /dev/stdin -X POST http://127.0.0.1:3080/api/session/list \
  -H \"Content-Type: application/json\" \
  -d \"{\\\"type\\\":\\\"client-request\\\",\\\"rpcId\\\":\\\"x\\\",\\\"method\\\":\\\"session/list\\\",\\\"payload\\\":{\\\"args\\\":{\\\"_request\\\":{}}}}\"'" \
  < /root/.hermes/state/8t-cookie.jar
# 期望 ok:true；若返回 unauthorized 说明 cookie 还是新壳的
```

### 第 2 步：重启中继

```bash
systemctl restart tg8-relay
sleep 45        # 首轮 getUpdates 长轮询要 45-60 秒，别急着判定失败
systemctl is-active tg8-relay
```

### 第 3 步：验证真的回到旧壳了

```bash
# ① 旧壳引擎还在吗（必须 401）
adb -s 100.93.28.42:5555 shell "su -c 'curl -s -o /dev/null -w \"%{http_code}\" -m 5 http://127.0.0.1:3080/'"

# ② 中继日志出现成功投递
grep 'forwarded -> engine' /root/tg8-relay/relay.log | tail -3

# ③ 真的能通——在 TG 群 @ 零八说一句话，看它回不回
```

**判据：三条全过才算恢复。只有进程在跑不算。**

---

## 切换前的检查清单（缺一不可）

| # | 检查 | 命令 / 判据 |
|---|---|---|
| 1 | 旧壳引擎健康（回退的前提） | `curl 127.0.0.1:3080` = **401** |
| 2 | 旧壳进程还在（没被冻） | `ps -A -o PID,ARGS \| grep com.deepseek.harness` |
| 3 | 新壳引擎健康 | `curl 100.93.28.42:13081` = **401** |
| 4 | 新壳能思考（模型凭据） | API 发一句话，能回 |
| 5 | 新壳能干活（su） | `su -c id` → `uid=0` |
| 6 | 新壳能读记忆 | `ov search` 返回真实结果 |
| 7 | 新壳能写记忆 | `ov note` 成功 |
| 8 | 新壳历史接上 | 问「我们最近在聊什么」能答对 |
| 9 | **新壳不放 tgsend**（零八的硬要求） | 新壳 dsh-tools 里没有 tgsend 可执行入口 |
| 10 | 新壳看起来像「刚搬过来的」 | AGENTS.md 有「换壳说明」一节 |

---

## 切换后 24 小时观察项

| 观察 | 怎么查 | 异常就回退 |
|---|---|---|
| 引擎冻结次数 | `cat /sys/fs/cgroup/uid_10295/cgroup.freeze` | 频繁 = 1 → 查三开关 |
| 引擎自愈是否工作 | `runtime/engine-host.log` | 卡死不恢复 |
| 记忆中继是否正常 | `relay.log` 的 `forwarded` / `tg/send` | 连续失败 |
| 会话是否在增长 | `du -sh dshhome/sessions` | 一直不涨 = 没在干活 |
| 有没有「两个零八」 | TG 群里发言数、来源 | **出现立刻回退** |

---

## 最坏情况

**新壳彻底坏了、旧壳也坏了**：

```
旧壳数据完好（sessions/AGENTS.md 都在原处，从未移动）
新壳数据完好（是复制，不是移动）
OpenViking 记忆完好（在 CT106，与手机无关）
→ 最坏也能从任意一边恢复
```

**这就是「复制不移动」的价值：整个迁移过程中，旧壳的数据从未被触碰。**
