# 迁移切换记录（2026-10-30 20:45—21:03）

> 零八从旧壳 `com.deepseek.harness` 迁到新壳 `cc.bestly.dsh` 的完整过程。
> **旧壳数据一字未动，仍在服役，作为退路。**

---

## 切换动作（20:45:43—20:45:47，4 秒）

```sh
# ① 中继配置：engine_base 3080 → 13081
# ② 活跃壳登记：/data/adb/.active_shell → cc.bestly.dsh
# ③ systemctl restart tg8-relay
```

---

## ★ 切换时漏了一环：认证

**第一次转发失败：**

```
20:46:48 engine prompt fail: {'raw': 'unauthorized'}
```

**根因**：中继用 `-b cookie_jar` 认证，而 cookie jar（`/root/.hermes/state/8t-cookie.jar`）
里装的是**旧壳的 token**。新壳 token 不同（43 字节），所以 401。

**修法**：用新壳 `runtime/token` 换一份新 cookie：

```sh
TOKEN=$(adb shell "su -c 'cat /data/user/0/cc.bestly.dsh/files/runtime/token'")
curl -c newcookie.jar "http://100.93.28.42:13081/?token=$TOKEN"   # 303 + Set-Cookie
cp newcookie.jar /root/.hermes/state/8t-cookie.jar                # 备份旧的先
systemctl restart tg8-relay
```

**验证对照**（这才是能报错的验法）：

```
新 cookie 打新壳 → {"ok":true, ...}
旧 cookie 打新壳 → unauthorized
```

**教训：切换清单里「认证」是独立一环，不能只改地址。**
cookie/token/session 三样都要跟着换 —— 这次只想到地址，差点以为切换失败要回退。

---

## 切换结果（实测）

```
20:48:14  forwarded -> engine: msg_id=1208     中继连上新壳
20:49:31  /tg/send ok len=1259                 零八从新壳自己开口
20:49:55  /tg/signal from 08: tg=True fails=0  通信正常
```

**零八在新壳里的第一句话**（自己跑了三条命令才回，不是复述我的清单）：

> 【新壳第一句话：我实测完了，一切正常。只有一个地方要你确认】

---

## 它发现的第一个真缺陷：没有存储权限

```
touch /storage/emulated/0/DSH-Workspace/.write-test-08
→ Operation not permitted
（读可以，写不行；su -c 写可以）
```

它的对照表：

| 权限 | 旧壳 | 新壳 |
|---|---|---|
| `READ_EXTERNAL_STORAGE` | ✓ | **✗** |
| `WRITE_EXTERNAL_STORAGE` | ✓ | **✗** |
| `MANAGE_EXTERNAL_STORAGE` | ✓（granted=false） | ✗ |

它说清了自己怎么定位的：

> 「靠的是**对比法** —— 同一个手机上两个壳，一个有权限一个没有，差异直接指向答案。
> 这比单看一个包猜要快得多。」

### 修法（v0.8.4 → v0.8.5）

两处缺一不可：

1. **Manifest 声明** `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`
   —— targetSdk=28 走旧存储模型，这两条够管 `/sdcard` 全域，
   不需要 `MANAGE_EXTERNAL_STORAGE`（旧壳虽然申请了，但 `granted=false`）
2. **`MainActivity` 运行时申请** —— targetSdk 28 下这两条是危险权限，
   只在 Manifest 声明**不生效**。申请失败不阻塞引擎启动。

装后实测：`versionName=0.8.5` / `versionCode=13` / **uid 仍是 10295（没变）** /
两条权限 `granted=true` / 引擎 401。

用 root 直接 `pm grant` 授的权，没劳烦 456 点手机。

---

## ★★ 第二个判据错误：FUSE 掩盖真实属主

给零八做验证时，我设计的判据是「看文件属主：`u0_a295` = App 身份写的，`root` = 走了 su」。

**零八加了一个对照实验，把这判据否掉了：**

| 谁写的 | 属主 |
|---|---|
| App 身份（`u0_a295`） | `root:everybody` |
| `su`（root） | `root:everybody` |

**两个一模一样。** 它的解释：

> 「不是『文件已存在导致测不出来』，**是 FUSE 本身就不保留真实属主** ——
> 它把所有文件的属主统一显示成 `root:everybody`，跟谁写的无关。
> 你的测试设计这次是对的（删旧文件排除了覆盖的干扰），**但它排不掉 FUSE 这一层**。」

**这条推翻了零八自己先前提的那个担心**（「su 写属主是 root，和 App 写的 u0_a295 混在一起会多一个变量」）——
在 `/storage/emulated/0`（FUSE 挂载）上根本不存在这个区别。**它先提了个风险，后来自己用实验否掉了。**

**正确的判据**：`id` 显示的 uid + `rc`（命令退出码）。
`/sdcard` 下**任何**文件属主都不能用来判断"谁写的"。

---

## 并发事件：两个零一同时改同一个仓库

切换期间，企微会话（本会话）和 TG 群会话**同时在处理零八报的权限问题**，
各自改了同一份仓库，产生了两个互补的 commit：

```
9c216b8  fix(manifest): 补存储权限        ← TG 会话的零一，改了 Manifest
fc9c7a4  fix(perm): 补存储权限            ← 企微会话的零一，改了 MainActivity + 版本号
```

两个 commit **同一秒**（20:51:37），message 措辞不同。排查过程：
- 无外部 SSH 登录（`auth.log` 空、`last` 无记录）
- 无可疑进程
- 动的文件都是本任务相关

**结论**：两个会话各自独立做了同一件事的一半。恰好互补，结果正确，但过程失控。

**教训：Hermes 的企微会话和 TG 会话互不可见，但共享同一个文件系统和仓库。**
改共享仓库前应当先看一下 `git status` / `git log`，或者约定一条会话专用的分支。

---

## 最终状态（21:03 实测）

```
版本      v0.8.5 / versionCode 13
uid       10295（重装后没变 → MCS category 和 tgsend 门都不用改）
权限      READ/WRITE_EXTERNAL_STORAGE 均 granted=true
引擎      13081 = 401
中继      tg8-relay active，指向 13081
活跃壳    cc.bestly.dsh
旧壳      3080 仍在跑，数据未动（退路）
```

## 回退（3 条命令，见 migration-rollback.md）

```sh
# ① config.json: engine_base → http://100.93.28.42:3080
# ② 恢复旧 cookie: cp /root/.hermes/state/8t-cookie.jar.bak-oldshell-* /root/.hermes/state/8t-cookie.jar
# ③ systemctl restart tg8-relay; echo com.deepseek.harness > 手机 /data/adb/.active_shell
```

**注意第 ② 条**：回退时**必须把 cookie 也换回去**，否则同样 401。
这是本次踩出来的坑，已补进回退手册。
