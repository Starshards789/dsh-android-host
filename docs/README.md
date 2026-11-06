# 文档索引

按用途分五类。每篇开头都有日期与实测来源，过时结论会标注「已推翻」而不是删掉。

## design/ — 设计与方案

| 文档 | 内容 |
|---|---|
| [`design-v2.md`](design/design-v2.md) | 安卓宿主设计方案 v2（755 行）。含已被推翻的方案，保留原文作决策痕迹 |
| [`memory-design.md`](design/memory-design.md) | 记忆系统设计 v1：四层 + 三钩子 + 5 步落地 |
| [`trust.md`](design/trust.md) | 升级闸门的信任根（Ed25519） |
| [`cross-shell-gate.md`](design/cross-shell-gate.md) | 换壳门：防止两个零八同时在线 |

## ops/ — 运维与实战记录

| 文档 | 内容 |
|---|---|
| [`engine-freeze.md`](ops/engine-freeze.md) | ColorOS 冻结机制与 33 次冻结的原始记录 |
| [`engine-selfheal.md`](ops/engine-selfheal.md) | v0.8.1→v0.8.4 四个 bug 与 generation 代号修法 |
| [`external-watchdog.md`](ops/external-watchdog.md) | 三层看护设计 + host-guard-v2 的六个坑 |
| [`migration-prep.md`](ops/migration-prep.md) | 换壳迁移第 ① 步：搬什么、不搬什么、三项能力实测 |
| [`migration-cutover.md`](ops/migration-cutover.md) | 第 ③ 步切换：漏了认证那一环 + FUSE 属主判据失效 |
| [`migration-rollback.md`](ops/migration-rollback.md) | 回退手册（3 步 + 10 项前置检查 + 24h 观察项） |
| [`oldshell-retire.md`](ops/oldshell-retire.md) | 旧壳退役：mailwatch 不能停 + 被拉起两次的两个原因 |

## reference/ — 基线与接口

| 文档 | 内容 |
|---|---|
| [`8t-system-baseline.md`](reference/8t-system-baseline.md) | 8T 系统与权限基线（26KB，12 章）：系统身份、权限三层、ColorOS 增量层、Oplus Hans、换系统评估 |
| [`a11y-bridge.md`](reference/a11y-bridge.md) | 无障碍读屏桥：三层结构、端点契约、三个伪装成别的问题的坑、零八验收与已知边界 |

## research/ — 调研

| 文档 | 内容 |
|---|---|
| [`memory-trigger.md`](research/memory-trigger.md) | 记忆第 1 步「任务收尾三问」+ 零八的回应 |
| [`industry-survey.md`](research/industry-survey.md) | 业界 Agent 记忆系统横向调研（MemGPT/Letta、Mem0、Zep、A-MEM、Cognee） |
| [`portable-implementations.md`](research/portable-implementations.md) | 可移植到 Node / 安卓的轻量记忆实现 |
| [`memory-layer-verification.md`](research/memory-layer-verification.md) | 记忆层实测：OpenViking 的 git 快照/回滚/embedding 四问（2026-11-07） |

## history/ — 历史归档

| 文档 | 内容 |
|---|---|
| [`CHANGELOG-20261106.md`](history/CHANGELOG-20261106.md) | 2026-11-06 夜间迭代的完整记录 |
| [`correspondence/`](history/correspondence/) | 与零八的往来信件原文（换系统决策、记忆系统讨论） |

## 其他

| 文档 / 目录 | 内容 |
|---|---|
| [`oss-reuse-log.md`](oss-reuse-log.md) | **开源借鉴台账**：读了什么、借鉴了什么、有没有用代码 |
| [`patches/`](patches/) | Android 适配补丁（升级时必须重打） |

---

新增文档时：放进对应目录，并更新本索引与根 `README.md` 的文档索引。
