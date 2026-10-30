# 可移植到 Node / 安卓的轻量 agent 记忆实现调研

> 核查时间：2026-10-29。所有仓库信息走 GitHub API 实抓（stars / language / license / pushed_at），
> `MemTensor/MemOS` 的 `apps/memos-local-plugin` 已 clone 源码读 `ARCHITECTURE.md`、`core/*/README.md`、`templates/config*.yaml`。
> 论文 ID 全部用 arXiv API 按 id 反查过标题。

## 0. 一句话结论

**别自己设计记忆结构——直接抄 `MemTensor/MemOS` 的 `apps/memos-local-plugin`。**
它是唯一一个「TypeScript + SQLite + 本地 ONNX embedding + L1/L2/L3 分层 + 明确的写入/提升阈值」全都齐了、
而且真在 Node 上跑的开源实现（MIT 子包，Node ≥ 22）。第二选择是 `tickernelz/opencode-mem`（libSQL 内嵌 + F32_BLOB 余弦，无扩展加载）。
其余项目：分层思想抄 MemoryOS / Letta，写入时机抄 claude-mem / LangMem，排序抄 Generative Agents。

---

## 1. 能直接在 Node 里跑的本地记忆层（最相关）

| 项目 | 语言 / 依赖 | Node / 安卓可行性 | 核心机制（一句话） | 借鉴度 |
|---|---|---|---|---|
| https://github.com/MemTensor/MemOS → `apps/memos-local-plugin` | TS，Node≥22，`better-sqlite3@13` + `@huggingface/transformers@4.2` | Node ✅（原生模块需编译）；安卓/Termux 需自编译 better-sqlite3 + onnxruntime，建议换远端 embedding | 任务结束→抽 trace(L1)→按 signature 分桶归纳 policy(L2)→抽象 world model(L3)→结晶 skill，全链路 SQLite 单文件 | **高** |
| https://github.com/tickernelz/opencode-mem | TS / Bun，`@tursodatabase/database`(libSQL) + `onnxruntime-node` | Node ✅；libSQL 是单文件内嵌，比 better-sqlite3 更省事；仍然依赖 onnxruntime 原生库 | 内嵌 libSQL 用 `F32_BLOB` 存向量，`vector_distance_cos` 做精确余弦，不需要装向量扩展 | **高** |
| https://github.com/thedotmack/claude-mem | TS，Node≥20，SQLite+FTS5，Chroma 可选 | Node ✅；Chroma 部分可关掉（只要 SQLite 全文检索） | 宿主生命周期 hook 触发写入 → AI 压缩成 observation/summary → 三层渐进披露检索 | **高** |
| https://github.com/mem0ai/mem0 （`mem0-ts/`，npm `mem0ai@3.3.1`） | TS | Node ✅，但 OSS 模式仍要外部 vector store + LLM | LLM 抽取事实后走 ADD/UPDATE/DELETE/**NOOP** 四算子合并进库 | 中高 |
| https://github.com/modelcontextprotocol/servers → `src/memory` | TS，Node≥24 | Node ✅，零依赖 | 单文件 `memory.jsonl` 存「实体-关系-观察」知识图谱，无向量 | 中 |
| https://github.com/supermemoryai/supermemory | TS | 需自托管服务/云；不是嵌入式库 | 事实抽取 + 用户画像 + 冲突/过期处理 + hybrid search | 中 |

**为什么 memos-local-plugin 是首选**（源码实证）：

- `package.json`：`better-sqlite3 13.0.3`、`@huggingface/transformers 4.2.0`、`engines.node >=22`、`license: MIT`。
- `ARCHITECTURE.md` 明确「agent-agnostic core + adapter」：`core/` 不认任何宿主概念，OpenClaw/DSH 用 in-process TS 适配，Hermes 走 JSON-RPC bridge。
  也就是说，**core 本身就是一个可独立调用的 `MemoryCore` facade**，安卓 agent 可以直接 import 或挂它的 HTTP server。
- 数据模型只有 11 张表：`sessions / episodes / traces(L1) / policies(L2) / l2_candidate_pool / world_model(L3) / skills / feedback / decision_repairs / audit_events / kv`。
- 默认 `algorithm.lightweightMemory.enabled = true`，即默认只跑 trace 层检索——作者本来就按「资源受限」在调。

---

## 2. 记忆分层（L0 常驻 / L1 检索 / L2 归档）的现成实现

| 项目 | 语言 | 分层结构 | 关键机制 | 借鉴度 |
|---|---|---|---|---|
| https://github.com/letta-ai/letta （MemGPT 官方实现，25k★） | Python（有 TS SDK） | **core memory blocks（常驻）/ recall（历史检索）/ archival（向量归档）** | block 以 XML 形式**常驻 prepend 进 prompt**，默认 read-write，agent 用 memory 工具自己改；可设 `read_only` | **高**（这是 L0 的标准答案） |
| https://github.com/BAI-LAB/MemoryOS （EMNLP'25 Oral） | Python | **short-term / mid-term / long-term** | 短期满 → Updater 切段进中期；中期按「访问频次 + 交互长度」累计 **heat**，超阈值（默认 5 / 13）才提炼进长期画像与知识库 | **高**（阈值提升，全自动、无 LLM 门控） |
| https://github.com/MemTensor/MemOS （L1/L2/L3） | TS | **trace（事实）/ policy（子任务策略）/ world model（环境认知 ℰ空间结构・ℐ行为规律・𝒞约束禁忌）/ skill** | 按「独立 episode 数」和 gain 提升，`status: candidate→active→retired` | **高** |
| https://github.com/Mirix-AI/MIRIX | Python | **Core / Episodic / Semantic / Procedural / Resource / Knowledge Vault** 六类 | 每类一个专属 agent 维护；PostgreSQL + docker | 中（结构可抄，跑不动） |
| https://github.com/agiresearch/A-mem | Python | Zettelkasten 笔记网 | 每条新记忆由 LLM 生成属性/标签/上下文，再与历史笔记建链，并**反向修订旧笔记** | 中高 |
| https://github.com/zilliztech/memsearch | Python | Markdown 为真源 + 3 层召回 + 第三层「技能」 | `search → expand → transcript` 渐进召回；sha256 去重 | 中高 |
| https://github.com/basicmachines-co/basic-memory | Python | Markdown 文件 + SQLite 索引 + wikilink 图 | 人可读、可 git diff 的知识库，MCP 暴露 | 中（**AGPL-3.0**，商用注意） |

**要点**：真正稀缺的不是「三层」这个概念，而是**层间迁移的判据**。三种可抄的判据：

1. **热度阈值**（MemoryOS）：`heat += f(访问次数, 交互长度)`，过阈值才上升一层 —— 零 LLM 成本。
2. **独立任务计数 + signature 分桶**（MemOS）：`signature = primaryTag|secondaryTag|tool|errCode`，同一桶内**不同 episode** 数 ≥ N 才调 LLM 归纳；同一 episode 内重复不算。
3. **价值增益 gain**（MemOS）：`gain = weightedMean(有该策略) − shrink(无该策略)`，支撑度不够就不激活。

---

## 3. 「什么时候写」的具体实现（本次调研的重点）

按触发粒度从粗到细，四类可抄的写法：

| 触发方式 | 实现 | 具体参数 / 细节 | 借鉴度 |
|---|---|---|---|
| **任务结束批量写** | MemOS `capture` | 监听 `episode.finalized`，一次 LLM 调用同时算任务级 `R_human`(rubric 三轴) + 每步反思权重 `α_t`，再回溯 `V_t = α_t·R + (1−α_t)·γ·V_{t+1}`；`batchMode: auto`（≤12 步一次调用）；**放弃的任务也写**（R=−1） | **高** |
| **宿主生命周期 hook 写** | claude-mem | `SessionStart / UserPromptSubmit / PreToolUse / PostToolUse / Stop / SessionEnd` 每个 hook 一条命令；PostToolUse 抓工具观测，SessionEnd 落 summary | **高** |
| **热度驱动提升** | MemoryOS | 短期写满才切段；中期 heat 过阈值才提炼长期 | **高** |
| **失败即写** | https://github.com/noahshinn/reflexion | 只在任务失败后写一段 verbal reflection 进情节缓冲，下次重试时读回 | 中高 |
| **agent 自己决定**（hot path） | https://github.com/langchain-ai/langmem | `create_manage_memory_tool` + `create_search_memory_tool` 交给模型在对话中调用；另有 background manager 做提取/合并 | 中高 |
| **agent 自己决定**（self-editing） | Letta memory blocks | block 常驻上下文，模型用 memory 工具 `insert/replace` 修改，可设 `read_only` 防误删 | **高** |
| **异步后台蒸馏** | https://github.com/NevaMind-AI/memU | 定时任务把新 session 日志切成 job 文件，**判断与归纳留给 agent 自己做**，`MemoryService` 只存/嵌入/检索（不调 LLM） | 中 |

**最重要的工程结论**：这些项目里**没有一个**让 agent 每一步都决定「要不要记」。
主流做法是 **事件驱动 + 事后批量**（episode 结束 / session 结束 / 任务失败），LLM 只在「归纳」和「打分」两处出现，且一次批量处理整段。
手机上这点尤其关键：写入是异步的，永不在工具调用链中间阻塞。

---

## 4. 向量 / 存储底座（Node 可跑）

| 项目 | 语言 | Node | 机制 | 借鉴度 |
|---|---|---|---|---|
| https://github.com/asg017/sqlite-vec | C（`npm i sqlite-vec`） | ✅ 通过 better-sqlite3 `loadExtension`；也有 WASM | `vec0` 虚表，float/int8/**binary** 向量，纯 C 无依赖，暴力检索 | **高**（配 1-bit 量化最省内存） |
| https://github.com/oramasearch/orama | TS，Apache-2.0 | ✅ 纯 JS，浏览器/边缘/Node 通吃 | 内存索引 + 全文/向量/hybrid，`@orama/plugin-data-persistence` 落盘 | 中高 |
| https://github.com/Stevenic/vectra | TS，MIT | ✅ 纯 TS，文件型 | 本地文件夹式向量库，自带 `LocalEmbeddings` / `TransformersEmbeddings`，有 SQLite storage 示例 | 中高（最轻的「文件+embedding」实现） |
| https://github.com/lancedb/lancedb | Rust core + Node SDK | ✅ 但原生二进制大（几十 MB 起） | 嵌入式列存 + ANN，进程内 | 中 |
| https://github.com/unum-cloud/usearch | C++（npm `usearch`） | ✅ 原生绑定 | 单头文件 HNSW，C++/JS/Python 绑定 | 中（安卓要自己编） |
| https://github.com/chroma-core/chroma | Rust | ❌ JS client 只是薄客户端，要 `chroma run` 起服务 | 客户端-服务器 | 低（不适合端上） |

> 你们已有 OpenViking 做语义检索 → **本地这层可以只要 SQLite + FTS5**，向量列留空或只存 384 维 int8 备用；
> 检索时「FTS5 召回候选 → OpenViking 重排」比在手机上跑 ANN 划算得多。

---

## 5. 手机上能跑的 embedding

| 方案 | 体积/形态 | 说明 | 借鉴度 |
|---|---|---|---|
| `@huggingface/transformers`（https://github.com/huggingface/transformers.js，Apache-2.0） | `Xenova/all-MiniLM-L6-v2`，**384 维，int8 量化约 23 MB** | memos-local-plugin 的默认实现：`pipeline("feature-extraction", {pooling:"mean", normalize:true})`，懒加载 + 进程内共享；支持 `HF_ENDPOINT` 指镜像 | **高** |
| https://github.com/qdrant-labs/fastembed-js （原 `Anush008/fastembed-js` 已归档） | ONNX，BGE-small 等 | TS API，`FlagEmbedding.init()` 批量生成 | 中高 |
| 远端 embedding API | 0 字节 | memos-local-plugin 内置 `openai_compatible` provider，README 明写兼容 Zhipu / SiliconFlow / Bailian —— **手机端最稳的路**：只在写入时调一次，几十 token | **高**（推荐） |
| ONNX BGE-M3 | ~558 MB | memsearch 提供该本地选项（`embedding.provider onnx`）；**手机上不要** | 低 |

安卓现实约束：`onnxruntime-node` 的 aarch64 预编译覆盖不全，Termux 里多半要自己编或直接改用远端 API。
`better-sqlite3` 在 Termux 能编（要 clang + python），但更省事的是 **libSQL（opencode-mem 那种）** 或 Node 22+ 内置 `node:sqlite`。

---

## 6. 论文（arXiv ID 已逐条核对）

| ID | 标题 | 可抄的点 |
|---|---|---|
| 2310.08560 | MemGPT: Towards LLMs as Operating Systems | 主上下文/外部存储两级 + 模型自己调 memory 函数换页 |
| 2309.02427 | Cognitive Architectures for Language Agents (CoALA) | working / episodic / semantic / procedural 四分类，写论文/写 schema 都用它对齐 |
| 2507.03724 | MemOS: A Memory OS for AI System | MemCube（plaintext/activation/parameter）、调度与生命周期 |
| 2505.22101 | MemOS: An Operating System for MAG | 同上短版 |
| 2506.06326 | Memory OS of AI Agent（MemoryOS） | 三级存储 + heat 提升公式 |
| 2502.12110 | A-MEM: Agentic Memory for LLM Agents | Zettelkasten 式笔记 + 动态建链 + 记忆演化 |
| 2507.07957 | MIRIX: Multi-Agent Memory System | 六类记忆的职责划分与 consolidation |
| 2504.19413 | Mem0: Production-Ready Long-Term Memory | 抽取→更新两阶段，ADD/UPDATE/DELETE/NOOP |
| 2501.13956 | Zep: A Temporal Knowledge Graph Architecture | 双时间轴（valid/invalid time）知识图谱，处理事实过期 |
| 2304.03442 | Generative Agents | 检索打分：`recency_w·(decay^i) + importance_w·poignancy + relevance_w·cos`，三者各自归一化；代码默认 `decay=0.99`、三个权重均 1 |
| 2303.11366 | Reflexion | 失败后写 verbal reflection 进情节记忆 |
| 2502.06975 | Position: Episodic Memory is the Missing Piece | 论述「情节记忆」而非「事实库」才是长任务的关键（和你们的排障场景高度一致） |

---

## 7. 如果我来落地（对着 OpenViking 的定位）

```
写入侧（抄 MemOS + MemoryOS）
  turn 结束 → 只落原始 turn（SQLite，无 LLM）
  episode 结束/任务成功或放弃 → 一次批量 LLM：任务评级 R + 每步反思 α → 回溯 V
  同时算 signature = 主标签|次标签|工具|错误码 → 进候选池
  候选池同桶 ≥N 个不同 episode → LLM 归纳成 policy（when X → do Y → verify Z）
  policy gain 达标 → active；被调用后按 Beta 后验更新可靠性；30 天未提升的候选淘汰

读取侧
  L0（常驻，≤1–2 KB）：项目/环境事实卡，Letta block 风格，带 read_only 标记，直接 prepend
  L1（检索）：trace/policy 两级，FTS5 粗召回 → OpenViking 语义重排 → MMR 去重
  L2（归档）：原始工具输出/长日志只存不检索，只作为归纳素材

自编辑
  给 agent 只开三个工具：memory_write(scope=fact|project) / memory_patch(block, old, new) / memory_forget(id)
  默认不开放删除；写 L0 要过字符预算检查
```

这套的关键取舍：**L0 用「结构化 block + 硬字符上限」而不是向量；L1 才谈检索；LLM 只在 episode 结束和归纳时出现一次。**
手机上真正跑得动的就是这个形状。
