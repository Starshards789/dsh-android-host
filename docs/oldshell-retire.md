# 旧壳退役（2026-10-30 21:12）

> 新壳切换成功并运行 25 分钟后，按 456 批准执行「停服务、保数据」。
> **数据一个字没删。**

---

## 一件不能一刀切的事：mailwatch 不能停

旧壳有 5 个常驻守护，看起来全是旧壳专用。查完发现 **2 个不能停**：

| 守护 | 服务谁 | 处理 |
|---|---|---|
| `freezewatch` | `uid_10284`（旧壳）冻结监控 | 停 |
| `engine-guard` | `PKG=com.deepseek.harness` + 3080 | 停 |
| `sentinel-guard` | 看护上面两个 + 旧壳手册 | 停 |
| **`mailwatch`** | **零八的收件箱（写死 3080）** | **改成指新壳，不停** |
| **`mailwatch-guard`** | 看护 mailwatch | 跟着留下 |

**mailwatch 停了零八就收不到信了。** 这一条差点漏掉——它的作用从名字上看不出来
（叫 "mailwatch"，实际是「信箱值守 → 有新信就 prompt 引擎提醒零八去取」）。

---

## mailwatch v4.1 → v5

| | v4.1 | v5 |
|---|---|---|
| 引擎地址 | 写死 `127.0.0.1:3080` | 读 `runtime/host` |
| 端口 | 写死 `3080` | 读 `runtime/port` |
| token | `grep 'token=' dsh-web.log \| tail -1` | 读 `runtime/token` |
| 失败日志 | 只有「token unavailable」 | 区分 `cookie fail (http=… host=… port=…)` |

**为什么必须改成动态读**：新壳引擎在僵死重启时会避让到下一个端口（实测 13081 → 13082）。
写死端口会在那种时刻**静默失效**——不报错、只表现为「信箱永远提示没新信」，
这是最难查的一类 bug（存得进、查不着、还不报错）。

**session id 不用改**：`.mail_session` = `session-6c0f2740-8951-45a2-a6f4-a7ccc77b335f`
就是那个 TG 群会话，原样搬到了新壳，id 不变。

---

## 执行过程

```
21:12:01  ① mailwatch v4.1 → v5，旧实例(2259)停，新实例(3279)起
21:12:07  ② freezewatch(9507) / engine-guard(9998) / sentinel-guard(9501) 停
21:12:07  ③ am force-stop com.deepseek.harness
21:12:11  验证（脚本内验证因 PATH 缺 curl 报错，改从本机验）
```

### 遇到的坎：force-stop 之后又被拉起来两次

**第一次**：`engine-guard` 没死透——`kill`（默认 SIGTERM）之后它还在，
把旧壳引擎又拉起来了（旧壳进程 3641 重新出现）。

修法：`kill -9`，并且**反复确认死透**（循环查进程，直到 `ps` 里确实没有了）。

**第二次**：`engine-guard` 死透、`force-stop` 执行成功，但旧壳进程 `4542` 又出现了。
查父进程 = `1084`（系统 zygote）——**是安卓系统自己拉起来的**，不是我们的脚本。

诊断：`3080=000`（引擎没跑），说明只是 App 空壳被系统唤醒（可能有前台服务或唤醒源）。

修法：`pm disable-user --user 0 com.deepseek.harness`
→ 状态 `disabled-user`，进程消失。

**这一步是可逆的**：`pm enable com.deepseek.harness` 就放出来。**数据不受影响。**

---

## 开机脚本：不处理的话重启手机全部复活

`/data/adb/service.d/` 里有 4 个旧壳相关的启动脚本，开机时会把守护重新拉起：

```
25-dshweb-fwd.sh      旧壳端口转发（iptables DNAT → 3080）
45-sentinel-guard.sh  启动 sentinel-guard
50-engine-guard.sh    启动 engine-guard
host-guard.sh         启动旧壳的 host-guard（120s，am start 那套）
```

处理：**改名 + 去掉执行权限**（双重保险）

```sh
mv "$f" "$f.disabled-oldshell-$(date +%Y%m%d-%H%M%S)"
chmod -x /data/adb/service.d/*.disabled-oldshell-*
```

改名后后缀不再是 `.sh`，Magisk 本就不会执行；`chmod -x` 是第二道保险——
**这种事不赌，两道都上。**

---

## 最终状态（21:13:59 实测）

```
手机侧进程
  2269  mailwatch-guard          （看护 v5）
  3279  mailwatch v5             （指新壳）
  9268  host-guard-v2 60         （新壳外部看护）
 30022  node 引擎               （新壳，端口 13081）
 30891  cc.bestly.dsh            （新壳 App）

引擎
  旧壳 3080 = 000（停）
  新壳 13081 = 401（健康）

旧壳
  pm 状态     disabled-user（可逆）
  数据        39 MB 完整保留（dshhome）
  开机脚本    4 个已改名禁用

中继
  tg8-relay active，指向 http://100.93.28.42:13081
```

## 回退（旧壳放出来，3 步）

```sh
# ① 放出来
adb shell "su -c 'pm enable com.deepseek.harness'"
# ② 起 App（引擎会自己起）
adb shell "su -c 'am start -n com.deepseek.harness/.MainActivity'"
# ③ 中继改回旧壳（含 cookie，见 migration-rollback.md 第 1 步 + 第 1.5 步）
```

**旧壳的 4 个开机脚本不用恢复**——手动起 App 就够，守护不是必需的。

## 一周后要做的（11-06）

如果新壳稳定：
1. 备份旧壳 39 MB 到 CT106（真要删之前的最后一道保险）
2. `pm uninstall com.deepseek.harness`（或保留禁用状态不动）
3. 删掉 4 个 `.disabled-oldshell-*` 脚本
4. 清理 `/data/adb/` 下旧壳相关的日志（engine-guard.log / sentinel-guard.log / host-guard.log）
