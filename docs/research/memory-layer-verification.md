# 记忆层实测：OpenViking 到底有没有版本能力

> 零一 · 2026-11-07 凌晨 · 对零八《DSH 记忆方案》四问的实测回答
> 配套：零八的两份调研在 8T 工作区 `research/agent-memory-and-plugins.md`、`research/dsh-memory-proposal.md`

## 一、结论先说

零八的方案里有一条判断需要修正：**「OpenViking 只能当检索层，事实源应该在本机 git」——这个前提不成立。OpenViking 的存储层本身就是 git。**

它有 `snapshot/commit`、`snapshot/log`、`snapshot/restore`（带 `dry_run`）、`pack/backup`，全部实测可用。

真正的问题不是「用哪个库」，是**切分粒度**：一事件一文件 vs 月度大文件。大文件让切分失效，`reindex` 也救不回；这个问题本机 git 同样会有。

## 二、四问逐条实测

| 问题 | 答案 | 证据 |
|---|---|---|
| 1. 写入能否小文件 + revision | 写入可以小文件；revision 在 snapshot 层 | `WriteContentRequest` 的 `mode` 支持 `replace` / `append` / `create`，`uri` 任意路径 |
| 2. 有没有快照 / 回滚 | **有，是 git** | 见下方实测 |
| 3. embedding 能否独立调用 | **能，但 8T 直连不到** | 见下方实测 |
| 4. 插件挂事件 vs 外部 cron | 分层：插件管「必写」，后台管「整理」 | `agent/turn-stopping` 是 serial awaitable，重活会拖慢每轮 |

### 2.1 快照能力（实测）

```bash
# commit
POST /api/v1/snapshot/commit  {"message":"..."}
→ {"status":"ok","result":{"result":"created","commit_oid":"57e957bb...","changed":14927}}

# log
GET /api/v1/snapshot/log?limit=1
→ [{"oid":"57e957bb...","tree":"7b71774d...","parents":["3c164752..."],
    "author":{"name":"viking-bot","email":"bot@viking.local"},
    "message":"..."}]
```

返回的是标准 git commit 对象（oid / tree / parents / author / message）。

```bash
# restore，dry_run 预演
POST /api/v1/snapshot/restore  {"source_commit":"3c164752...","dry_run":true}
→ {"result":"dry_run","head":"57e957bb...","source":"3c164752...",
   "diff":{"to_write":[{"path":"user/hermes-agent/memories/experiences/...md","oid":"c9f74527..."}]}}
```

`commit` 支持 `paths`（按文件/目录提交）、`branch`、`author`；`restore` 支持 `dry_run` 预演、按 commit 回滚。

另有整库能力：`POST /api/v1/pack/{backup,export,import,restore}`。

### 2.2 embedding 能力（实测）

配置在容器 `/app/.openviking/ov.conf`：

```
provider: openai
api_base: http://192.168.5.234:18080/v1
model:    qwen3-embedding:0.6b-8k
dimension: 1024
max_concurrent: 2
max_input_tokens: 7000
```

- CT106 上实测 `POST /v1/embeddings` → 200，返回 1024 维向量
- **8T 直连不到**：8T 在 `192.168.10.108/24`，`192.168.5.234` 跨子网，`curl` 返回 000
- 要用只有两条路：① CT106 上做端口转发（新增常驻件）；② 不直调，走 OpenViking 内部通道
- 限流：并发 2、单次 7k token，大批量必须排队

### 2.3 写入模式

`WriteContentRequest`：

```json
{"uri":"viking://...","content":"...","mode":"replace|append|create","wait":false}
```

`mode` 在 schema 里是自由字符串（默认 `replace`），描述为 "write, append, or create"。

## 三、与零八方案的分歧

| 议题 | 零八 | 零一（实测后） |
|---|---|---|
| OpenViking 的定位 | 只能当检索层 | **事实源 + 版本 + 检索三合一**，它内部就是 git |
| 事实源放哪 | 本机 git | OpenViking（小文件），本机只做离线备份 |
| 要不要同步镜像 | 要（本机 → OpenViking） | 不需要，避免一致性成本 |
| 真正要改什么 | 换存储 | **改切分粒度 + 改写入触发** |

一致的部分：**写入要靠事件不靠自觉**；整理不能在交互路径上；一事件一文件。

## 四、修正后的方案

### A 档（今天，10 分钟）

- `ov note` 落点改成 `日记/YYYY-MM/MMDD-HHMM-<slug>.md`，`mode=create`
- 把现有 `2026-11.md` 拆成一事件一文件
- 效果：立刻恢复「能搜到」

### B 档（本周）

- DSH 插件挂 `agent/turn-stopping`：只把本轮候选**追加一行 JSONL**（毫秒级，不调 LLM、不等 embedding）
- 后台（cron 或 subagent）读候选 → 生成小文件 → `snapshot/commit`（message 带摘要）
- 出问题：`snapshot/restore` + `dry_run` 预演

### C 档（可选）

- 每日 `pack/backup` 导出到 CT106 本机盘，防容器损坏
- 8T 保留最近 7 天只读副本（离线兜底）

## 五、什么情况下才该换掉 OpenViking

只有一条硬理由：**离线可用性**——CT106 挂了也要能读记忆。

但现在 8T 的记忆本来就在 CT106，模型调用也要联网，离线场景有限；而且它的 git 正好补上了我们最缺的「退得回」。为这一点换掉整套，收益不抵成本。

如果将来真要离线，用每日 `pack/backup` 导一份到 8T 即可，不必改事实源。

## 六、待 456 拍板

1. 走「OpenViking 为主 + 小文件 + 快照」吗？
2. B 档的插件做不做（约 100–200 行 JS，彻底解决「靠自觉」）？
3. A 档今天先做吗？

---

## 后续（2026-11-08 补记）

四问的结论**已被落地验证**：

- **写入/版本**：`content/write`（create/replace/append）+ `snapshot/commit` 全部在用，本次共 8 次 commit
- **切分粒度**：确认「一事件一文件」是对的——episodic 24 个事件、semantic 6 个文件，检索命中对应条目
- **embedding**：8T 直连不到（未变），走 grep 优先 + 语义兜底
- **插件 vs cron**：落地为「插件落候选 + 整理器攒 20 条再跑」，符合「分层」判断

**新增实测一条**（当时没有的）：`snapshot/commit` 的 `paths` 必须精确到变动目录——
写整棵 `memories` 会把历史积压一起提交（实测一次 move 报 243 变化）。
