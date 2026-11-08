# 8T 当前设备基线（2026-11-08 实测）

> **来源**：零八的记忆库 `semantic/devices/8t.md`（OpenViking），由零一起草、零八核验写入。
> 核验项：WiFi `zhuaps-5G` / Magisk 30.7 / 引擎端口 13081 均实测一致。
> 判据：**会变的值不写死，只写去哪查**（PID、运行时长、心跳）。
> 本文是仓库里的镜像，权威版在零八记忆库；改动请先改那边再同步回来。

---

---
kind: semantic
status: active
confidence: confirmed
source: "零一起草 2026-11-08 + 零八核对"
updated: 2026-11-08
---

> 只写「现在仍然成立」的。会变的值（PID、运行时长、心跳）不写死，写「去哪查」。

## 1. 硬件

- 型号：OnePlus 8T（KB2000），骁龙 865
- 存储：256G；内存：12G
- SIM：**未插卡**
  - **依据**：456 于 2026-11-08 明确说明「8T 现在没有手机卡」
  - 456 于 2026-09-23 提过要给零八办一张，**尚未办**
  - `gsm.sim.state = ABSENT,ABSENT` 与此一致，**不是故障**；基带能扫到基站也不能证明卡已识别
  - 插卡后要做：纯蜂窝链路验收（Tailscale 蜂窝路径 + ADB）、设成不受流量限制

## 2. 系统

- ROM：LineageOS 24.0-20261030-NIGHTLY-kebab / Android 17
- SELinux：Enforcing
- Root：Magisk 30.7；`su` 在 `/debug_ramdisk/su`
- 写 `/data/adb` 必须用 `/debug_ramdisk/magisk su`（普通 `su -c` 走 shell 域写不进）
- `/data/adb` 的 SELinux context 必须是 `magisk_file`

## 3. 网络

- WiFi：`zhuaps-5G`
- Tailscale IP：`tailscale ip -4` 查（当前 `100.100.161.114`）
- ADB：`<tailscale-ip>:5555`；CT106 侧必须先 `adb connect`（TCP 设备不会自动出现）
- 防火墙：`/data/adb/service.d/40-dsh-firewall.sh` 开机重建，只放行 `lo` + `tun*`

## 4. 零八服务

- 壳：`cc.bestly.dsh`；版本用 `dumpsys package cc.bestly.dsh | grep version`
- 引擎：`0.0.0.0:13081`；PID 读 `runtime/pid`，端口读 `runtime/port`（不要猜端口，它可能漂移）
- 健康判据：`curl -o /dev/null -w '%{http_code}' http://<tailscale-ip>:13081/` → **401 = 正常**
- 中继：CT106 的 `tg8-relay`，`engine_base` 必须跟随手机 Tailscale IP 变更

## 5. 关键路径

| 用途 | 路径 |
|---|---|
| dshhome | `/data/data/cc.bestly.dsh/files/dshhome` |
| 工作区 | `/sdcard/DSH-Workspace` |
| runtime | `dshhome/files/runtime`（pid / port / token / host / restart / heartbeat） |
| 记忆候选 | `dshhome/memory/candidates` |
| 工具集 | `/data/adb/dsh-tools/`（tgsend / pw / dsh-upgrade / host-guard-v2） |
| 插件 | `dshhome/profiles/web/node_modules/` |

## 6. 凭据位置（只写位置，不写值）

| 凭据 | 位置 |
|---|---|
| dsh 凭据 | `dshhome/.credentials.yaml` |
| OpenViking key | `/data/adb/dsh-tools/.ovkey` |
| Vaultwarden key | `/data/adb/dsh-tools/.pwkey` |
| Vaultwarden 服务 | CT106 `bw serve`（127.0.0.1:8087）+ nginx `bw-serve` |

## 7. 开机自启

`/data/adb/service.d/` 七个脚本：

```
05-wifi-connect          开机连 WiFi
10-adb-tcp               开 ADB TCP + ctl.restart adbd
20-tailscale-start       拉 Tailscale
30-tailscale-watchdog    保活
40-dsh-firewall          重建防火墙（动态探测 tun*）
50-mem-collect-boot      开机补采一次候选
60-loopback-fwd          回环转发（127.0.0.1:13081 → tun1，作用明确）
```

## 8. 待补 / 已修未验 / 易误判

### 8.1 待补（真缺口）

- **代理内核缺失**：`/data/adb/proxy` 不存在，`px` 用不了 → 零八出不了境（GitHub 等被墙）
  - 状态：重刷后未装回
  - 补法：需要 456 给配置

### 8.2 已修未验

- **双引擎竞态**：1.3.3 起已加 `startGuard`（`AtomicBoolean` 并发闸）+ `adoptExistingEngine`（认领已存活实例）
  - 修复有效性**未实测** —— 触发条件是概率事件（`restart` 轮询 15s / 健康检查 60s 同秒相遇），人工难复现
  - 验证信号：下次真发生时 `host.log` 出现 `start 并发调用被拦（已有启动在执行）`

### 8.3 易误判（都不是故障）

- **SIM 未插卡** —— 456 已确认
- **无障碍装/升级后丢** —— 用 `su -c settings put` 补一次（组件名从 `dumpsys package` 查，不要猜）
- **`60-loopback-fwd.sh` 存在** —— 作用明确（127.0.0.1 → tun1），不算冗余

## 9. 版本事实（写死会过期的，只作参照）

- 当前壳：1.3.3 / versionCode 34（2026-11-08 装）
- payload：0.2.1-rc.2
- 核验方法永远优先于这里的数字

<!-- MEMORY_FIELDS
{
  "version": 1
}
-->