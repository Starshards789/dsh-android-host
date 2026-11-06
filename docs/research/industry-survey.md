# 业界 Agent 记忆系统横向调研（面向手机端 DSH Agent）

调研时间：2026-10。所有机制均来自一手来源（论文 / 官方文档 / 官方仓库 README），
每条给出出处，未查到的部分明确标注"未公开"。

---

## 0. 一句话先说清分野

业界所有方案其实在回答同一个问题：**"谁来决定写什么、什么时候写"**。答案只有三种：

| 范式 | 谁决定写入 | 代表 | 代价 |
|---|---|---|---|
| A. 模型自编辑（LLM-OS） | 模型自己在每步调工具决定分页 | MemGPT / Letta | 每步多一轮 tool call，需要强模型 |
| B. 框架自动抽取（管道） | 框架在 turn 边界跑抽取 + 合并 | Mem0 / Zep / A-MEM / Cognee | 每次写入 1~N 次 LLM 调用，写放大 |
| C. 分层落盘 + 离线巩固 | 热路径零 LLM 落盘，空闲时批量巩固 | OpenAI cookbook / Memobase / LlamaIndex | 有时序滞后（刚说的不一定马上进画像） |

手机端只能选 C 为主、B 的简化版为辅、A 基本抄不了。理由在 §5。

---

## 1. 主对比表

| 系统 | 写入方式 | 检索方式 | 衰减 / 更新 / 遗忘 | 定位 | 手机可抄度 |
|---|---|---|---|---|---|
| **MemGPT / Letta** | 模型自编辑：`core_memory_append/replace`、`archival_memory_insert` 等工具调用，全自动但由模型发起 | 主上下文（pin 块）+ `archival_memory_search`（pgvector 语义）+ `conversation_search`（全量消息库）；检索带分页防爆窗 | 无时间衰减。靠 token 水位触发：70% 警告 → 100% 驱逐 ~50% 消息 + 递归摘要；被驱逐消息永久留在 recall storage 可找回 | 长期陪伴（论文即 multi-session chat + 人设一致性） | ★★☆ 块结构可抄，自编辑机制不可抄 |
| **Mem0** | 两阶段管道全自动：抽取（LLM 从 message pair 抽候选）→ 更新（向量找 top-k 相似 → LLM 选 ADD/UPDATE/DELETE/NOOP） | 混合四信号：语义向量 + 关键词 BM25 + 实体 + 时间；SQL 做 source of truth，向量库做召回 | 靠 UPDATE/DELETE 覆写；无时间衰减、无"历史版本"概念 | 任务会话 + 轻陪伴 | ★★★★ 写路径思想可抄，全自动抽取需降频 |
| **Zep / Graphiti** | 全自动管道：episode 入库 → 实体抽取/消解 → 事实边抽取 → 时间抽取 → 冲突失效 | 三路子图（episode/entity/community）；cosine + BM25 + BFS 三路召回 → RRF/MMR/节点距离/cross-encoder 重排 → 拼成带日期的 FACTS+ENTITIES 文本 | **双时间轴 + 事实失效不删除**：每边 4 个时间戳（valid_from/valid_to/observed/recorded），矛盾时把旧边 `t_invalid` 设为新边 `t_valid`；社区用 label propagation 增量维护，定期全量刷新 | 任务/企业为主，也能做长陪伴 | ★★★★ 双时间戳可抄，图库+社区不可抄 |
| **LangChain / LangGraph** | 两条路都支持：hot path（模型调 `save_memories` 之类工具实时 upsert）或 background（会话结束后异步抽取） | 短期 = thread checkpoint 全量 state；长期 = Store（namespace + key 的 JSON 文档，支持语义检索 + 字段过滤） | 无内建衰减，靠开发者自己写合并/删除逻辑；官方明确说"profile 每次整体重写，collection 要模型自己删改，容易 over-insert / over-update" | 通用框架，不预设场景 | ★★★★★ 存储抽象与取舍最值得抄 |
| **LlamaIndex Memory** | 半自动：短期 FIFO 满了按 `token_flush_size` 冲刷给长期 memory block，block 自己决定是抽事实还是存向量 | 短期 FIFO（按 token 预算）+ 长期 blocks 合并；`VectorMemoryBlock` 按"消息批次"检索（`similarity_top_k` + `retrieval_context_window`） | **按 priority 截断**：`priority=0` 永不截断，其余超预算时按优先级从低到高丢弃；`FactExtractionMemoryBlock` 有 `max_facts` 上限，超了自动摘要压缩 | 通用 agent | ★★★★★ 块+优先级+预算截断，几乎是手机端最优解 |
| **Cognee** | 批处理管线 ECL：`add`（38+ 格式，规范化+hash 去重）→ `cognify`（6 阶段：分类/权限/chunk/抽实体关系/摘要/embed+提交边）→ `memify`（剪 stale 节点、加强高频连接、按使用信号重加权、补派生事实） | 14 种检索模式，从经典 RAG 到图遍历 / CoT；向量 + 图双写 | 靠 `memify` 定期重跑做"记忆维护"，不是实时 | 文档/知识型任务 | ★★★ 增量重跑思想可抄，图管线不可抄 |
| **A-MEM** | 全自动，写入时三件事：① note construction（LLM 生成 keywords/tags/context）② link generation（与全库 cosine top-k → LLM 判断真关系）③ memory evolution（LLM 回头改邻居的 context/tags） | **只用** query embedding 与 note embedding 的 cosine top-k（论文 k=10）；链接不参与打分，只用于演化与可解释 | "遗忘"= 邻居被新记忆重写属性；无删除、无衰减 | 研究原型 | ★★ 写放大严重（O(N) + k 次额外调用） |
| **Hindsight** (2025-12) | 全自动，粗粒度叙事抽取：每次对话只抽 **2–5 条**自包含叙事事实（不是逐句），带时间区间 (τs,τe,τm)、参与者角色、fact type | 四路并行：语义 + 关键词 + 图遍历（4 种边）+ 时间过滤 → 融合 → cross-encoder 重排；**按 token 预算 k 返回，不是固定 top-k** | 不删除：fact 带时间区间；opinion 带 confidence ∈[0,1]，新证据来临时做强化/削弱 | 长期陪伴（明确为"long-lived agents"设计） | ★★★★ 四网络分层 + confidence 可抄 |
| **MemOS** | 统一 MemCube 容器 + 调度器；写入/召回可异步（MemScheduler）；已有官方 **DSH 适配器** | 本地 SQLite + FTS5 + 向量混合检索；scheduler 按"使用频率/最近使用"决定加载哪些 | 生命周期治理（归档/更新/权限），论文层面有，工程细节未完全公开 | 通用 OS 级 | ★★★★★ 有现成 DSH 集成参考 |
| **Memobase** | 全自动但**批量**：per-user buffer 攒着，一次固定 **3 次 LLM 调用**跑完抽取+画像更新 | **不做向量检索**：读结构化画像（几条 SQL）+ 事件时间线，在线 <100ms | 画像字段被新值覆写；时间线 append-only | 长期陪伴（明确面向 companion / 教育 / 助理） | ★★★★★ 手机端性价比最高 |

---

## 2. 逐家机制拆解（关键数字）

### 2.1 MemGPT（arXiv 2310.08560）/ Letta

**主上下文三段式**：
1. `system instructions` — 只读，含记忆层级说明 + 每个函数的自然语言描述
2. `working context` — 定长、可写、只能通过函数调用改；本质是"关于用户和人设的关键事实"
3. `FIFO queue` — 滚动消息队列，含 user/assistant/系统消息（记忆警告）/函数输入输出；**第 0 位存一条"被驱逐消息的递归摘要"**

**外部上下文**：`recall storage`（全部消息，永久，可 `conversation_search`）+ `archival storage`（任意长文本 + 向量检索）。

**分页机制（最值得看的工程细节）**：
- prompt 超过 `warning token count`（如上下文 70%）→ 队列管理器**插入一条 system 消息**警告模型"即将驱逐"，让模型自己把重要信息写进 working context 或 archival
- 超过 `flush token count`（100%）→ 驱逐约 **50%** 的消息，用"旧递归摘要 + 被驱逐消息"重新生成摘要，放回 FIFO[0]
- 被驱逐的消息**不丢**，永久在 recall storage
- 检索函数内置**分页**，避免一次检索把窗口打爆
- 函数可带 `request_heartbeat` 标志实现**函数链**：连续调用多个函数不返回用户；不带则是 yield，等下个外部事件

**Letta 的演进（比论文更重要）**：
- 论文里的 working context 变成了 **memory block**：`label` + `value`（字符串）+ `size limit`（字符/token）+ 可选 `description`；每块独立持久化在 DB，有 `block_id`，开发者可直接改
- prompt 不是拼接而是从 DB 状态**"编译"**出来（Jinja 模板）
- 块可**多 agent 共享** → 支撑"sleep-time agent"：空闲时后台 agent 反思历史、把 learned context 写进共享块，主 agent 下次启动就带着
- 工具名（官方确认）：`core_memory_append` / `core_memory_replace` / `archival_memory_insert` / `archival_memory_search` / `conversation_search` / `send_message`

**必须知道的一个坑**：MemGPT 的招牌成绩 DMR 93.4%，而 Zep 论文里**全上下文基线（gpt-4-turbo）就有 94.4%**、gpt-4o-mini 有 98.0%。DMR 每段对话只有 60 条消息，塞得进窗口。**MemGPT 的优势不在短对话，在"窗口装不下"之后**。别拿 DMR 当说服自己的理由。

### 2.2 Mem0（arXiv 2504.19413）

**论文版两阶段**：
- **抽取**：输入 = 会话摘要 S（异步定期刷新）+ 最近 m=10 条消息 + 当前 message pair，输出候选事实集合 Ω
- **更新**：每个候选 → 向量检索 top-k=10 相似记忆 → LLM 通过 function call 在四选一里选：ADD / UPDATE / DELETE / NOOP
- 论文明确写了优先级约束：**NOOP > DELETE > UPDATE > ADD**（先判"不用动"，再判"矛盾要删"，再判"补充"，最后才是新增）
- 实验用 GPT-4o-mini；LOCOMO 上 LLM-as-Judge 比 OpenAI 方案相对提升 26%；p95 延迟 -91%，token -90%
- **Mem0g**：抽取时额外产出 entity/relation 三元组，存成有向标签图；update 期在图上做冲突检测

**⚠️ 重要更新（当前 OSS v3 已改，很多二手文章没跟上）**：
- 抽取改成 **single-pass ADD-only**：一次 LLM 调用，**不再有 UPDATE/DELETE**
- 检索改成三信号混合：semantic + BM25（需 spaCy 做词形还原）+ entity matching
- 图记忆从 OSS **移除**，变成 Platform 专有功能
- 官方给的收益：LOCOMO 71.4 → 91.6，LongMemEval 67.8 → 93.4，抽取延迟约减半

**这条演进本身就是结论**：连 Mem0 自己都在生产里砍掉了"每写一条就 LLM 判 ADD/UPDATE/DELETE"的复杂写路径，换成"便宜地多写 + 检索时再排序"。手机端更该这么干。

检索的四个信号（Platform 版）：semantic / keyword / entity / temporal（用写入时抽的时间元数据和 query 的时间意图比对）。

### 2.3 Zep / Graphiti（arXiv 2501.13956）

**三层子图**：
1. **episode 子图** — 原始 message / text / JSON，非有损；每条带 `t_ref` 参考时间戳，用于解析"下周四""两周前"这类相对时间
2. **semantic entity 子图** — 实体节点 + 事实边（edge 带 relation type 和完整事实描述；同一事实可跨多实体，用 hyper-edge 表达）
3. **community 子图** — 强连通实体聚类的摘要（map-reduce 生成），社区名含关键词并 embed，供 cosine 检索

**双时间轴**：T = 事件真实发生的时间；T' = 系统摄入的时间。
每条边 4 个时间戳：`t_valid` / `t_invalid`（属于 T）、`t'_created` / `t'_expired`（属于 T'）。

**构建细节**：
- 实体抽取时喂"当前消息 + 前 n=4 条消息"（两个完整 turn）做 NER；**说话人自动作为实体**
- 用 reflexion 式二次反思降低抽取幻觉
- 实体消解：实体名 embed 成 1024 维 → cosine 找候选 + 对 name/summary 做全文检索找候选 → LLM 判定是否同一实体，重名则生成新的 name + summary
- 写库用**预定义 Cypher 查询**而不是让 LLM 生成查询（防幻觉、保证 schema 一致）
- 事实去重只在**同一对实体之间**的边里做 hybrid search，大幅缩小搜索空间

**冲突处理**：LLM 比较新边与语义相关的旧边，发现**时间区间重叠的矛盾** → 把旧边的 `t_invalid` 设为新边的 `t_valid`。**不删除**。T' 上"新信息优先"。

**社区维护**：用 label propagation（不是 Leiden），因为可以增量扩展——新节点归入"邻居里占多数的社区"，然后更新社区摘要；代价是会逐渐偏离全量结果，所以仍需定期全量刷新。

**检索三步 f(α)=χ(ρ(φ(α)))**：
1. `search`：cosine（语义）+ Okapi BM25（词面）+ BFS（上下文邻近，可用"最近的 episode"当种子）
2. `rerank`：RRF / MMR / episode-mentions 频率（被反复提到的更容易被取到）/ node distance（离质心近的优先）/ cross-encoder（最贵）
3. `constructor`：拼成 `FACT (Date range: from - to)` + `ENTITY_NAME: summary` 的文本

**效果（LongMemEval，gpt-4o）**：上下文 115k → **1.6k tokens**，延迟 28.9s → **2.58s**，准确率 60.2% → **71.2%**。
**代价**：single-session-assistant 类问题掉了 17.7%（图记忆对"助手自己说过什么"这种逐字召回不友好）。

### 2.4 LangChain / LangGraph 与 LlamaIndex

**LangChain 的旧 memory 类已全部弃用**（`ConversationBufferMemory`、`Window`、`Summary`、`Entity`、`VectorStoreRetrieverMemory`），当前是 LangGraph 的两层：

| 层 | 机制 | 特点 |
|---|---|---|
| 短期 | thread-scoped **checkpointer**，整个 state 落库 | 可 resume、可时间旅行（replay 到任意 step） |
| 长期 | **Store**：`namespace`（像文件夹）+ `key`（像文件名）的 JSON 文档 | 支持语义检索 + 字段过滤，可跨 namespace 搜 |

官方给出的三个关键取舍（这段是全文最有价值的部分，因为它把权衡说透了）：

1. **Profile vs Collection**
   - Profile = 一个持续更新的 JSON 文档。每次都要"把旧 profile + 新对话交给模型生成新 profile"（或 JSON patch）。profile 变大后极易出错，需要 strict decoding。
   - Collection = 一堆独立文档。**更容易写新条目，召回更高**；但"删/改旧条目"变成模型的责任，模型会**过度插入或过度更新**（官方原话）。
   - 结论：写新容易、改旧难 —— 这是所有记忆系统的通病。

2. **hot path vs background 写**
   - hot path：实时、可告知用户，但增加延迟、要模型多任务、往往要额外加一个工具。**ChatGPT 就是这么干的**：用 `save_memories` 工具按条 upsert 字符串。
   - background：不占延迟、可批量、可挑时机（定时 / cron / 手动触发），但有时序滞后，且要决定"多久写一次"。

3. **三类记忆的落地方式**：semantic（事实）/ episodic（经历，实践上多用 few-shot 例子）/ **procedural（改自己的 prompt，靠 reflection / meta-prompting）**。

**LlamaIndex 的 Memory 类是"块 + 优先级 + 预算"的教科书**：
- 短期：`token_limit`（默认 30000）、`chat_history_token_ratio`（默认 0.7，超过就把最老的消息冲刷出去）、`token_flush_size`（默认 3000，一次冲多少）
- 长期：冲刷出来的消息交给 memory block 处理，取的时候短期+长期合并，总 token 必须 ≤ `token_limit`
- 三种内置块：
  - `StaticMemoryBlock(priority=0)` — 静态内容，永不截断（这就是"pin 块"）
  - `FactExtractionMemoryBlock(llm, max_facts=50, priority=1)` — LLM 抽事实；**事实数超上限就自动摘要压缩**
  - `VectorMemoryBlock(vector_store, embed_model, similarity_top_k, retrieval_context_window, priority=2)` — 按"消息批次"存和取
- **截断规则**：超预算时按 `priority` 从**大到小**丢弃（0 永不丢）
- `insert_method` 决定块注入到 system message 还是最后一条 user message
- 默认存储是本地 SQLite（`AsyncDBChatStore` 可替换）

### 2.5 Cognee / A-MEM / 其他 2025–2026 新框架

**Cognee** — 把"记忆维护"做成**可重跑的批处理管线**而不是实时计算：
- `add`：38+ 格式（PDF/CSV/JSON/音频/图片/代码）→ 规范化纯文本 → hash 去重 → 按 dataset 分组带权限
- `cognify`：6 个有序 task —— 文档分类 → 权限检查 → chunk → **LLM 抽实体和关系** → 生成摘要 → embed 入向量库 + 提交边到图库；**只处理新增/变更的文件**
- `memify`：剪掉 stale 节点、加强高频连接、按使用信号给边重加权、补派生事实（不重新 ingest）
- `search`：14 种模式，从经典 RAG 到图遍历 / CoT
- `node_sets` 做标签式分组和多租户隔离
- 核心价值：**增量 + 可重跑**。手机上"充电时跑一次 memify"就是这个思路。

**A-MEM（NeurIPS 2025，arXiv 2502.12110）** — Zettelkasten 式原子笔记：
- note = `{content c, timestamp t, keywords K, tags G, context X, embedding e, links L}`
- embedding = `enc(concat(c, K, G, X))` —— **把 LLM 生成的上下文描述也编进向量**，这是它检索准的主要原因
- link generation：与全库算 cosine → top-k 邻居 → **LLM 判断哪些是真关系**（不是阈值截断）
- memory evolution：对被链接的邻居，LLM 决定是否更新它的 context / keywords / tags，然后**替换原记忆**
- 检索：query embedding 与 note embedding 的 cosine top-k（k=10）。**注意：链接不参与检索打分**，只服务于演化和可解释性 —— 这点常被误读
- 成本：1,200–2,500 tokens vs MemGPT 的 ~16,900（省 85–93%）
- 问题：每次写入都要全库比对 + k 次额外 LLM 调用改邻居，**写放大极严重**

**Hindsight（arXiv 2512.12818，2025-12，MIT，vectorize-io/hindsight）** — 目前唯一把"事实/经历/看法"分开存的：
- **四网络**：`world`（客观事实）/ `experience`（agent 自己的经历，第一人称）/ `opinion`（主观信念，`(text, confidence∈[0,1], timestamp)`）/ `observation`（对实体的偏好中立摘要）
- **三操作**：`retain` / `recall` / `reflect`
- 写入：**粗粒度叙事抽取，每次对话只抽 2–5 条**自包含事实（"与其存五条碎片，不如存一条叙事"），带时间区间 (τs,τe,τm)、参与者角色、fact type
- 图：4 种边 —— temporal（权重 `exp(-Δt/σt)`，**天然的时间衰减**）/ semantic（cosine > θ）/ entity（共享实体双向连）/ causal（`causes`/`caused_by`/`enables`/`prevents`）
- 检索：**四路并行**（语义 + 关键词 + 图遍历 + 时间过滤）→ 融合 → cross-encoder 重排 → **按 token 预算 k 返回**
- reflect：用 disposition 参数（skepticism / literalism / empathy 各 1–5 + bias strength 0–1）调制生成，并顺手形成/强化 opinion
- 效果：20B 开源模型 LongMemEval 39.0% → **83.6%**
- 核心贡献：**"陪伴型 agent 的人设一致性"本质上是 opinion 网络的置信度演化问题**，不是检索问题

**MemOS（arXiv 2507.03724，Apache-2.0，12.1k★）**：
- `MemCube` 统一容器，明文记忆 / 参数记忆 / 激活记忆三类可互相转换
- 三层：接口层（memory API）/ 操作层（组织、检索、调度）/ 基础设施层（治理、存储、load-dump、共享）
- scheduler 按"使用频率、最近使用"等属性决定加载哪些记忆（这是论文里最有意思的一点：把记忆当缓存管理）
- **有官方 DSH 适配器**（out-of-tree bundle，不改 DSH 仓库）——详见 §4

**Memobase（Apache-2.0，2.9k★）** — 反潮流方案，值得单独看：
- **不做 RAG、不做知识图谱**，只做"结构化用户画像 + 事件时间线"
- 画像是一棵结构树：`basic_info`（name/age/语言/学历/婚姻）/ `interest`（游戏/YouTube 频道/食物）/ `psychological`（目标）/ `work`（行业/职位）
- per-user buffer 攒对话，批量跑，**每次 run 固定 3 次 LLM 调用**（官方从 3–10 次优化而来，省 40–50% token）
- 在线延迟 **<100ms**，因为读画像就是几条 SQL
- 核心洞察：**绝大多数"陪伴"需求只需要"一份画像 + 一条时间线"，向量库是过度工程**

---

## 3. 官方最佳实践（OpenAI / Anthropic）

### 3.1 OpenAI（Agents SDK cookbook: "Context Engineering for Personalization"）

这是目前最完整的一份"官方记忆设计文档"，核心是**两阶段 + 三层**：

**三层结构**：
1. **结构化 profile**（YAML/JSON）：稳定的、字段化的（座位偏好、会员等级、护照有效期）—— 可以用 tool 参数直接传，不必进 prompt
2. **global memory notes**（Markdown）：跨会话的持久偏好，每条 `{text, last_update_date, keywords}`
3. **session memory notes**：**暂存区（staging area）**，本次会话的临时上下文（"这次是家庭旅行，预算 2000 以内"）

**两阶段**：
- **Distillation（蒸馏）**：会话进行中，模型通过 `save_memory_note(text, keywords)` 工具把候选记忆写进 session notes。也可以改成"会话结束后用完整执行轨迹抽取"（能捞到工具调用模式和内部推理）
- **Consolidation（巩固）**：会话结束后**异步**跑，把够格的 session notes "毕业"进 global memory。官方原话：**这是整个生命周期里最敏感、最容易出错的阶段**，必须显式处理三件事：去重、冲突消解（如"新信息优先"）、**遗忘**（剪掉 stale / 低置信 / 被取代的）

**输出要求里有几条直接可抄的工程约束**：
- 稳定且反复被确认的偏好 → **提升**成结构化 profile 字段
- 易变 / 依赖上下文的偏好 → 留在 notes 里，配 **recency weighting / confidence score / TTL**
- 注入时机：**在 context trimming 之后、agent 开始执行之前**，通过 hook 注入到 system prompt
- 注入安全：用分隔符包裹记忆；**优先级 = 当前用户消息 > 会话上下文 > 记忆**；记忆是 advisory 不是 authoritative
- 三道 guardrail：**distillation 时**拒敏感模式 / 拒指令形态的载荷；**consolidation 时**"no invention" + 去重 + TTL；**injection 时**分隔符 + 优先级 + recency 加权
- 建议记录的指标：`memory_write_rate`（每百轮，高=噪声大）、`blocked_write_rate`、`memory_conflict_rate`、`time_to_personalization`
- 一句判断标准：**"如果 agent 记得上次的某件事，会不会明显帮助当前任务？"** 答案不明确就先别做

另外 ChatGPT 产品层的拆法是：**Saved memories**（显式要求记住的，单独存储，删原对话不影响）vs **Reference chat history**（从历史对话里隐式推理，会随模型更新变化）。两者可分别关闭。

### 3.2 Anthropic

**memory tool（`memory_20250818`，2025 年发布，Claude 4 及之后模型可用）**：
- 本质是**客户端实现的文件系统**，6 个命令：`view`（可带 `view_range` 按行读）/ `create` / `str_replace`（唯一匹配才改）/ `insert`（按行插）/ `delete` / `rename`
- 路径前缀固定 `/memories`，**存储完全在你的应用侧**（本地文件、DB、云存储都行），Anthropic 只发请求
- 模型被要求在开任务前**先去 view 记忆目录**；路径穿越必须由你的 handler 拦（`../`、`%2e%2e%2f`）
- 官方建议的配套：**compaction**（服务端摘要整段对话）+ **context editing**（客户端清掉特定 tool result）+ memory tool 三者组合 —— "compaction 让活跃上下文保持小，memory 保住那些必须活过摘要的信息"
- 安全建议里有一条很少人注意：**"定期删除长期未被访问的记忆文件"** + **限制单文件大小** + **限制 view 返回字符数，让模型用 view_range 翻页**

**context engineering 博客的三条正交手段**：
1. **Compaction** — 接近窗口上限时摘要，保留架构决策/未解决 bug/实现细节，丢掉冗余工具输出
2. **Structured note-taking** — agent 自己维护 `NOTES.md` / to-do list，**上下文重置后靠读自己的笔记续上**（Claude Plays Pokémon 就是这么跨摘要保持连贯的）
3. **Sub-agent** — 子 agent 烧几万 token 探索，只回 1000–2000 token 的蒸馏结论

作者给出的选择建议很实用：**要长对话流 → compaction；有明确里程碑的迭代开发 → note-taking；并行探索型研究 → 多 agent**。

---

## 4. 已经有人给 DSH 做过这件事（可直接照抄的集成范式）

`MemTensor/MemOS` 的 `apps/memos-local-plugin/adapters/deepseek-harness/` 是一个**树外 bundle（不改 DSH 仓库）**，它把"独立会话的 agent 怎么长出跨会话记忆"这件事的工程细节全写明了。这是本次调研里对你最直接有用的东西：

| 决策点 | 它的做法 | 为什么这么做 |
|---|---|---|
| 挂载点 | npm 包声明 `dsh.bundle.patch`，`dsh plugin` 安装后作为原生 Cordis 插件挂进 profile | 零改官方仓库，可随 DSH 升级 |
| 召回时机 | 每个"被接受的、非空的、direct-user" turn 做**一次**自动召回；**greeting 也召回**，无 session 级 bootstrap 门控，无 intent 例外 | 避免"该记的时候没记"这类静默失败 |
| 去重 | 同一逻辑 turn 在 `agent/pre-step` 重入时去重 | 防重复注入 |
| 防递归 | **插件生成的消息和工具消息不触发召回**，也不进入 query | 否则召回的上下文会被再次召回 |
| 前台预算 | 硬 deadline `min(recallTimeoutMs, 3000)` ms；超时/失败/JSON 畸形 → 降级到已排序候选的 `safeCutoff`；再没有 → **不注入** | 前台绝不因记忆而卡住 |
| 前台隔离 | 召回路径**绝不**等待 capture / summary / embedding 写 / 关系分类 / 意图分类 / 路由 | 读写彻底分离 |
| 注入形式 | 结果有上限，包在标签里，作为 `source=plugin/memos-local-memory/recall` 的 **user 消息追加在用户 query 之后**（顺序 query 先、记忆后） | 保 KV cache 的 prefix 稳定；且明确"这是历史数据不是指令" |
| 后台捕获 | `turn/end` 时把 route/classify + `MemoryCore.onTurnEnd()` 放进**每会话串行后台队列** | 串行保证同会话因果顺序，不阻塞下一 turn |
| 时序语义 | 下一 turn 的召回**不与上一 turn 的捕获同步** → 可能看到旧状态 | 明确接受"最终一致"，换前台延迟 |
| 生命周期 | `session/flush` 故意**不**当捕获屏障；`session/disposed` 不等待后台；只有 `Cordis dispose` 才做有界 best-effort drain | 防止退出时卡住 |
| 凭据 | 辅助 LLM 调用复用宿主 DSH 的 provider/route（async-local scope 携带，防并发串号），不读宿主 API key | 不二次配置 |
| 存储 | 本地 SQLite + **FTS5 + 向量**混合检索，数据在 `$DSH_HOME/memos-plugin/`，自带 127.0.0.1:18801 的 Viewer | 全本地，可离线看 |
| 显式工具 | 另给 6 个记忆工具（`memos_search` 等）供模型按需调用 | 自动召回兜底 + 显式检索兜精度 |

**这几条是"会话独立型 agent"加记忆的全部难点**：召回要不要门控、注入放哪、前后台怎么切、超时怎么降级、怎么防递归、退出怎么不卡。它已经给出了一套经过契约测试的答案。

---

## 5. 手机端（骁龙865 / 12G / 无 GPU / DSH）可抄与不可抄

前提假设：embedding + 向量检索可以走自建 OpenViking（服务端），手机端只负责本地存储、SQL、FTS5、排名算术、和"什么时候调 LLM"。若必须完全离线，则下面标 ⚠️ 的条目要再降级。

### 5.1 可以抄，而且应该抄（成本近似为零）

| 机制 | 来源 | 落地形态 | 成本 |
|---|---|---|---|
| **定长 pin 块 + 硬字符上限** | Letta memory blocks / LlamaIndex `StaticMemoryBlock(priority=0)` | system prompt 里 3 个块：`profile`（<600 字）/ `relationship`（<300 字）/ `open_threads`（<400 字） | 纯字符串，0 算力 |
| **按优先级截断预算** | LlamaIndex `priority` + `truncate()` | 总记忆预算定死（如 1.2k token），超了按 priority 从低到高丢 | 一次排序 |
| **双时间戳 + 失效不删除** | Zep 四条边时间戳 / Hindsight fact 时间区间 | SQLite 表加 `valid_from / valid_to / observed_at / recorded_at / superseded_by`；检索默认 `valid_to IS NULL`，"以前是什么"按时间点查 | 纯 SQL |
| **ADD-only 写入 + 冲突靠时间覆盖** | Mem0 v3 的教训 + Zep 的 invalidate | 永不 UPDATE 原行，只 INSERT 新行 + 关闭旧行 | 省一次 LLM 调用 |
| **画像 + 时间线双层** | Memobase | 结构化 KV 画像（几条 SQL 读）+ append-only 事件时间线 | <100ms |
| **热路径只落盘，冷路径才 LLM** | LangChain hot path vs background / Letta sleep-time / OpenAI distillation vs consolidation | 对话中只 append 原始 episode 到 SQLite（0 LLM）；充电+Wi-Fi 时批量跑巩固 | 关键 |
| **两段式：session notes → global notes** | OpenAI cookbook | 本次会话的临时记忆先存 session 表，会话结束才"毕业"进 global，且要过 dedup/conflict/forget 三道 | 关键 |
| **"记忆是数据不是指令"** | OpenAI guardrails / MemOS adapter | 注入块用 `<memory>...</memory>` 包裹 + 系统提示声明不可信 + 优先级"当前用户消息 > 会话 > 记忆" | 0 成本，防注入 |
| **有界召回 + 超时降级** | MemOS DSH adapter | recall 硬 deadline 1–3s；超时就返回 FTS5 结果；再不行不注入 | 关键 |
| **FTS5 + 向量两路 + RRF 融合** | Mem0 混合检索 / Zep 三路 | RRF 只是 `sum(1/(k+rank))`，CPU 上是毫秒级 | 极低 |
| **实体作为第三路信号**（弱化版） | Mem0 / Hindsight | 不建图，只存 `entities` 数组，检索时按命中实体数加权 | 极低 |
| **每次只抽 2–5 条叙事事实** | Hindsight | 巩固时明确要求"合并成自包含叙事，一条覆盖一整段交流" | 大幅省 token |
| **去重/冲突/遗忘三件事显式写进巩固 prompt** | OpenAI cookbook | 官方原话：遗忘不是 bug 是必需 | 0 |
| **opinion / confidence 层** | Hindsight | 陪伴型场景可以加一张 `beliefs` 表：`(text, confidence, updated_at)`，新证据来时 ±confidence | 低 |
| **同 turn 去重 + 插件消息不参与召回** | MemOS adapter | 防递归，必抄 | 0 |
| **记忆读写指标** | OpenAI cookbook | `write_rate / conflict_rate / time_to_personalization` | 0 |

### 5.2 抄不了 / 抄了会翻车

| 机制 | 为什么不行 |
|---|---|
| **模型自编辑记忆（MemGPT 每步 tool call 决定分页）** | 每步多一轮推理，手机上直接体现为发热、掉电、响应变慢；小模型上工具调用还容易乱。必须改成"框架决定何时召回/写入，模型只负责用" |
| **每次写入都跑 LLM 抽取（Mem0/MemGPT/A-MEM 实时路径）** | 一轮对话 3–10 次额外 API 调用，延迟和成本都不可接受。Mem0 自己都改成单次调用 + ADD-only 了 |
| **图数据库 + 社区检测 + 社区摘要（Zep / Cognee）** | Neo4j/FalkorDB 内存吃不消；label propagation 全量刷新在 865 上是分钟级 |
| **每次写入与全库比对 + 反向改邻居（A-MEM memory evolution）** | O(N) 写放大 + k 次额外 LLM 调用，写一条记忆的成本是读一条的几十倍 |
| **cross-encoder 重排 / 1024 维 embedding 本地推理** | 无 GPU。只能交给 OpenViking 服务端，或直接放弃重排、用 recency+entity 加权替代 |
| **用向量库当唯一记忆** | 手机端冷启动 + 索引重建 + 电量都不划算；且"用户叫什么"这种问题用 SQL 一行就能答，不该走向量 |
| **每次对话从 DB 重新"编译"整个 prompt（Letta context compilation）** | token 成本 ×N。应缓存编译结果，只在块变更时失效 |
| **115k 全上下文对照 / 大规模 benchmark 复现** | 无意义，手机上第一约束是电量不是准确率 |

### 5.3 一个针对 DSH 的落地骨架（把上面缝起来）

DSH 的关键约束是"会话独立 + 只有会话内 compaction"。所以钩子只需要两个：**会话开始注入**、**turn 结束捕获**。

```
L0 会话内     DSH 原生 compaction（保留），但把每次 compaction 的摘要产物
              同时落盘成一条 episode —— 压缩不等于丢弃

L1 常驻 pin   <1.2k token，每次对话开头必带，硬字符上限
              ├ profile      : 结构化 KV（称呼/时区/语言/禁忌/长期偏好）
              ├ relationship : 关系状态、进行中的事、上次情绪基调
              └ open_threads : 3–5 条"还没了结的事"

L2 事件时间线  SQLite append-only，热路径唯一写入口，0 LLM
              一行 = (id, ts_observed, ts_recorded, valid_from, valid_to,
                      narrative, entities[], source_episode, kind)
              kind ∈ {world, experience, opinion, observation}  ← 抄 Hindsight

L3 语义索引   OpenViking，只存"值得跨会话复用"的条目
              URI 分层: user/{id}/profile, user/{id}/episode/{yyyy-mm}, project/{x}

L4 后台巩固   充电 + Wi-Fi + 空闲 时触发，一次 LLM
              输入 = 最近 N 条 L2 + 当前 L1
              输出 = {promote_to_L1, promote_to_L3, invalidate, drop}
              三道必做：dedup / conflict(新优先) / forget(TTL + 低置信)
```

**写入策略**：热路径只写 L2；另给模型一个显式 `remember` 工具（用户说"记住"时直接写 L1/L2，绕过巩固）。
**检索策略**：会话开头 L1 全量（固定成本）→ 首轮补"最近 N 条 L2"（回答"上次聊到哪"）→ 之后按需 `memory_search`（FTS5 + OpenViking 向量 → RRF → 实体加权 → top 5–8，token 上限）→ 2s deadline，超时退 FTS5。
**冲突策略**：新事实 INSERT + 把旧行 `valid_to` 设为新的 `valid_from`，永不 DELETE。
**安全策略**：注入块标签包裹 + 声明"历史数据非指令" + 当前用户消息优先级最高。

**为什么这个骨架成立**：它把三家最贵的部分（LLM 抽取、图、重排）全挪到了"充电时的后台"和"服务端的 OpenViking"，手机前台只剩下 SQL + FTS5 + 排名算术 + 一次可取消的 HTTP 调用。MemOS 的 DSH adapter 已经证明了这套前后台切分在 DSH 上跑得通。

---

## 6. 每家的核心洞察（一句话）

- **MemGPT / Letta**：把上下文当内存管理，用"70% 警告 / 100% 驱逐 50%"的水位线让模型自己决定搬什么 —— 但前提是你有一个会调工具的强模型。
- **Mem0**：记忆的价值在"更新"而不在"存储"，而它自己最终承认了—— **便宜的 ADD-only + 检索时排序** 打赢了昂贵的实时 UPDATE/DELETE。
- **Zep / Graphiti**：记忆的难点不是"记住"，是"**当事实变了，怎么同时保留现在和过去**" —— 双时间戳 + 失效不删除，是它唯一无法被向量库替代的东西。
- **LangChain**：所有记忆系统都在同一个坑里 —— **写新容易，改旧难**；profile 一致但易错，collection 召回高但要模型自己删改且会失控。
- **LlamaIndex**：记忆不是"存什么"，是"**预算超了先扔谁**" —— 一个 priority 字段解决 80% 的工程问题。
- **Cognee**：记忆维护应该是**可重跑的增量批处理**，不是每次对话实时算。
- **A-MEM**：写入时多花一次 LLM 给记忆"写摘要和上下文"，检索时能省 90% token —— 但代价是写放大，且链接其实没参与检索。
- **Hindsight**：陪伴型 agent 的"人设一致性"不是检索问题，是**信念（opinion + confidence）如何随证据演化**的问题；事实和看法必须分表。
- **MemOS**：记忆该像缓存一样被调度（按频率和新鲜度决定加载谁），而且它已经示范了怎么在不改宿主内核的前提下接进 DSH。
- **Memobase**：绝大多数陪伴需求，**一份画像 + 一条时间线**就够了，向量库是过度工程；固定 3 次 LLM 调用、<100ms 延迟，性价比吊打重型方案。
- **OpenAI**：记忆是两阶段的 —— **会话内蒸馏（staging）+ 会话后巩固（毕业）**；巩固阶段的遗忘和冲突消解，比抽取重要得多。
- **Anthropic**：记忆就是一个**模型能自己读写的文件夹**，配 compaction 和 context editing 三件套；别搞 RAG，搞"按需 just-in-time 去读"。

---

## 7. 参考来源

| 主题 | 来源 |
|---|---|
| MemGPT | arXiv 2310.08560（全文 HTML，§2.1–2.4 为主要依据） |
| Letta memory blocks | letta.com/blog/memory-blocks；docs.letta.com/guides/agents/memory；docs.letta.com/v1-sdk/memory/archival-memory |
| Letta sleep-time | arXiv 2504.13171 |
| Mem0 论文 | arXiv 2504.19413（§2.1 两阶段、§3 实验） |
| Mem0 当前实现 | docs.mem0.ai/core-concepts/memory-types（How Mem0 Works）；docs.mem0.ai/migration/oss-v2-to-v3 |
| Zep / Graphiti | arXiv 2501.13956（§2 构建、§3 检索、§4 实验）；getzep.com/ai-agents/temporal-knowledge-graph |
| LangChain memory | docs.langchain.com/oss/python/langgraph/memory（含 profile/collection、hot path/background） |
| LlamaIndex Memory | docs.llamaindex.ai/en/stable/module_guides/deploying/agents/memory/ |
| Cognee | cognee.ai/how-cognee-builds-ai-memory；docs.cognee.ai（cognify / memify） |
| A-MEM | arXiv 2502.12110 v2（§3.1–3.4）；github.com/agiresearch/A-mem |
| Hindsight | arXiv 2512.12818 v1（§3 四网络、§4 TEMPR）；vectorize-io/hindsight |
| MemOS | arXiv 2507.03724；github.com/MemTensor/MemOS；apps/memos-local-plugin/adapters/deepseek-harness/README.md |
| Memobase | github.com/memodb-io/memobase |
| OpenAI 记忆实践 | developers.openai.com/cookbook/examples/agents_sdk/context_personalization；openai.github.io/openai-agents-python/sessions/；help.openai.com Memory in ChatGPT |
| Anthropic 记忆实践 | platform.claude.com/docs/en/agents-and-tools/tool-use/memory-tool；anthropic.com/engineering/effective-context-engineering-for-ai-agents |

**未查到 / 未公开**：Zep 的 Konig 内部实现；MemOS 的调度器工程细节（论文层面有，代码层未逐行核对）；ChatGPT "Reference chat history" 的具体检索实现（OpenAI 只说"用相关信息"，无技术细节）。
