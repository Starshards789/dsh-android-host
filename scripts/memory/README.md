# memory/ — 记忆系统脚本（零八维护）

> 落地记录见 `docs/design/memory-design.md`「0. 实现状态」。
> 这些脚本跑在 8T（Android 17 / Node 26），**不是 dsh 包**，由 cron + service.d 调用。

## 脚本

| 脚本 | 作用 | 调用方 |
|---|---|---|
| `mem-collect.js` | 扫 dsh 会话文件 → 候选 JSONL（`candidates/YYYY-MM-DD.jsonl`） | cron 每 2h + `service.d/50-mem-collect-boot.sh` |
| `mem-organize.js` | 候选打分 + 30min 聚类 → dry-run 清单（`--write` 才落盘） | 手动 / cron（攒 ≥20 条） |
| `mem-search.js` | 分层检索：默认 `episodic+semantic`，`--deep` 加 `日记/`，`archive` 永远排除 | 手动 |
| `mem-metabolism.js` | 访问统计（`last_used`/`access_count`/`weight`）——**只统计，不参与排序** | 手动 / `mem-search` 自动 touch |
| `mem-revision.js` | 变更流程：`move <from> <to> <reason>` 移走 + 记 log + 精确 paths 的 commit | 手动 |
| `mem-collect-boot.sh` | 开机补采一次（`service.d`，root 身份） | Magisk 开机 |

## 依赖

- Node：`/data/user/0/cc.bestly.dsh/files/payload/runtime/bin/node`（需 `LD_LIBRARY_PATH` 指向同目录 `lib`）
- OpenViking key：`/data/adb/dsh-tools/.ovkey`（root 可读）
- 数据目录：`$DSH_HOME/memory/`（`candidates/`、`metabolism.json`、`revisions.jsonl`）
- **OpenViking 地址（环境变量）**：`OV_HOST` / `OV_PORT`，默认 `127.0.0.1:1933`
  - 8T 上需显式设 `OV_HOST=<CT106 tailscale IP>`（OpenViking 不在本机）
  - 公开仓库不写死内网 IP，避免暴露 tailnet 拓扑

## 已知约束（实测）

1. **采集器不需要 root**（dshhome 属主是 App uid），**整理器需要**（读 `.ovkey`）
2. **单实例锁**：`mem-collect.js` 用 `mkdir memory/.collect.lock`，boot 与 cron 撞车时后者跳过
3. **zsleep 只有 root 能用**：`service.d` 拉起的脚本可用，App 身份被 SELinux 拒
4. **commit paths 必须精确到目录**：写整棵 `memories` 会把历史积压一起提交

## 路径说明

脚本里硬编码了 8T 的路径（`/data/adb/dsh-tools/`、`/storage/emulated/0/DSH-Workspace/`）。
换设备需要改的是：`DSH_HOME`、`ovkey` 路径、`node` 路径。
