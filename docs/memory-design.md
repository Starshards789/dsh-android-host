# 记忆系统设计（v1 · 2026-10-30）

> 本文是**设计文档**，不是已实现的功能。当前进度见 README 的路线表。
> 所有技术结论都标注了实测来源；未经实测的推测明确标注「未验证」。

---

## 0. 一句话

**dsh 没有跨会话记忆，这是结构性的（官方 309 个包里 0 个记忆包）。我们不重造记忆内核，而是：原文留在 dsh 自己的日志里，摘要与分类进 OpenViking，触发嵌进任务流。**

---

## 1. dsh 自带的「记忆」是什么

扫过设备上全部 309 个官方包，含 memory / recall / rag / embedding 的：**0 个**。

dsh 只做**单次会话内的上下文管理**：

| 机制 | 包 | 行为 | 状态 |
|---|---|---|---|
| 持久化 | `dsh-session-persistence-jsonl` | `session.v4.jsonl.zstd` 仅追加日志 | ✅ 完整 |
| 压缩 | `dsh-compaction-basic` | 到 80% 触发，LLM 摘要，逐字保留最新 16% | ✅ 有损 |
| 剪枝 | `dsh-compaction-tool-result-pruner` | 工具结果头/中/尾截断，不调模型 | ✅ |
| 检查点 | `dsh-session-checkpoint-policy` | 副作用前落盘 | ✅ |
| 检索 | `dsh-experimental-session-search` | 历史会话搜索 | ⚠️ 未启用 |

**结论：跨会话 = 失忆。** 这是设计决定的，不是 bug。

---

## 2. 压缩的真相（重要）

### 2.1 参数

`dsh-compaction-basic` 默认值（README.zh.md line 62–68）：

```
thresholdRatio = 0.80    上下文窗口用到 80% 触发
retainRatio    = 0.16    逐字保留最新的 16%
                    → 最多 84% 历史被摘要替换
```

### 2.2 但原文不丢

`dsh-session` README line 12 原话：

> **「压缩会在活跃对话中隐藏被取代的条目，但不会删除它们。」**

配套三条硬规矩（`dsh-session-persistence-jsonl` README）：

| 原文 | 出处 |
|---|---|
| 「把每个会话存为当前的**仅追加** JSONL 日志」 | line 12 |
| 「**已提交事件绝不重写**」 | line 74 |
| 「**不删除会话文件** —— seam 无删除接口」 | line 157 |

机制：压缩在日志尾部追加 `{op:'replace', startSeq, endSeq}` + 一条摘要，原始事件原地不动。

```
日志（永久）：  A B C D E F G H  + [replace A~E] + [summary]
模型看到的：    [摘要(A~E)] F G H
```

**注意一个容易误读的句子**：`dsh-session` README line 108 说「不存在原始日志回退，因此 surface 是派生历史的唯一来源」。这句不是说原文丢了，是说**模型运行时不会自动回退读原文** —— 要读得我们自己从日志里读。

---

## 3. 十三个会话事件，三个能直接用

从设备上 `dsh-session` 源码枚举（字符串常量）：

```
session/created   turn/start   turn/end★   session/title
compaction/start  compaction/prune  compaction/summary★★  compaction/end
session/flush  session/end-seed  session/disposed★  session/event
session/title-llm-request
```

| 钩子 | 时机 | 用途 | LLM 成本 |
|---|---|---|---|
| `turn/end` | 每轮结束 | 原始 turn 落库 | 0 |
| `compaction/summary` | 压缩发生时 | 直接复用已生成的摘要 | **0（已花过钱）** |
| `session/disposed` | 会话销毁 | 标记封存、算 signature | 0 |

**白捡的便宜**：dsh 每次压缩本来就要调一次 LLM 生成摘要（README: 「压缩使用一次额外的模型请求」）。订阅 `compaction/summary` 等于免费得到一份按时间分段的会话精华，事件里还带 `startSeq`/`endSeq` 能精确指回原文。**它压得越狠，摘要越浓缩，正好是记忆要的形态。**

⚠️ **未验证**：事件名存在 ≠ 订阅方能拿到 payload。动手前必须先写探针把三个事件的 payload 打出来（P6 的 `output.render` 就是这么栽的：schema 全过、插件显示加载成功、一调就炸）。

---

## 4. 业界三范式与手机端约束

所有记忆系统都在回答「谁来决定写什么、什么时候写」，只有三种答案：

| 范式 | 谁决定写入 | 代表 | 手机上能用吗 |
|---|---|---|---|
| A 模型自编辑 | 模型每步调工具 | MemGPT / Letta | ❌ 每步多一轮推理，865 上发热掉电 |
| B 框架自动抽取 | 每轮边界跑 LLM | Mem0 / Zep / A-MEM | ⚠️ 一轮对话 3–10 次额外调用 |
| **C 热路径落盘 + 离线巩固** | 对话中零 LLM，充电时批量 | OpenAI cookbook / Memobase | ✅ **只能走这条** |

已验证的原则：

- Mem0 v3 砍掉 LLM 的 ADD/UPDATE/DELETE 判断，改成 **ADD-only + 检索时排序**，准确率 71.4 → **91.6**
- Zep 双时间戳，事实变了**失效不删除**，同时保留现在和过去
- L0 用「结构化块 + 硬字符上限」，**不用向量**；只有 L1 才谈检索

抄不了的：模型自编辑、实时 LLM 抽取、图数据库、cross-encoder 重排、拿向量库当唯一记忆。

---

## 5. 中文全文检索的坑（实测）

在设备 Node **v26.4.0** 上实测 `node:sqlite`：

| 存储方式 | 搜「冻结」(2字) | 搜「解冻」(2字) | 搜「冻结了」(3字) | 搜 ColorOS |
|---|---|---|---|---|
| 原样存（FTS5 unicode61） | **0 命中** ✗ | — | — | 1 ✓ |
| **字符级预分词**（每字插空格） | **1 命中** ✓ | **1 命中** ✓ | — | 1 ✓ |
| trigram 分词器 | 0 命中 ✗ | — | 1 ✓ | — |

**中文 2 字词（绝大多数技术词）只有「字符级预分词」能搜到。** trigram 需要 ≥3 字。

```js
// 写入和查询都要过这个函数
function segCJK(s) {
  return s.replace(/([\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af])/g, ' $1 ')
          .replace(/\s+/g, ' ').trim();
}
```

**这个坑不提前踩，结果是「存得进去、检索永远返回空、还不报错」** —— 和 2026-08 那次 DeepSeek key 截断 1 位导致 3763 次 401 而服务一直显示健康，是同一类失败。

其他已验证：`node:sqlite` 建表/查询 ✅、JSON 存取 ✅、FTS5 可建 ✅、trigram 可用 ✅。**底座在 8T 上完全够用，不需要装任何原生模块。**

---

## 6. 架构：四层 + 三钩子

```
L0 常驻（<1.2k token）
   一页纸：我是谁 / 眼下在做什么 / 最致命的 N 条 / 常驻件现状 / 开工三步 / 不要做的事
   硬字符上限，超了按优先级丢

L1 事件（SQLite，append-only）
   热路径唯一写入口，0 LLM
   行 = (ts_observed, ts_recorded, valid_from, valid_to, narrative, entities[], kind)
   kind ∈ {world, experience, opinion, observation}

L2 语义（OpenViking）
   只存「值得跨会话复用」的条目
   摘要只当索引，必须保留 startSeq/endSeq 指回原文

巩固（充电 + WiFi + 空闲）
   唯一花 LLM 的地方：输入近期 L1 + compaction 摘要 → 输出 promote/invalidate/drop
   三道必做：去重 / 冲突(新覆盖旧，不删) / 遗忘(TTL + 低置信)
```

模型侧只给三个工具：`memory_write`（只写 fact/project 两种 scope）、`memory_search`、`memory_read`。**默认不开放删除。**

**分工原则**：
- dsh 日志 = 原文（本地、append-only、永久）
- OpenViking = 摘要 + 分类 + 语义检索（跨会话、跨设备）
- L0 一页纸 = 每轮必读的身份与状态

**不要在 OpenViking 里重复存原文** —— 那是双份维护，且会加剧检索噪声。

---

## 7. L0 该怎么写（抄零八的 HANDOFF.md）

零八在旧壳里已经有一套，实测：

```
AGENTS.md      53,439 字节   ← 工程手册（dsh 自动注入）
HANDOFF.md      3,106 字节   ← 真正的 L0，一页纸（/sdcard/DSH-Workspace/mailwatch/HANDOFF.md）
```

**HANDOFF.md 的结构（41 行）：**

```
1. 我是谁            （身份、设备、称呼纪律、语言）
2. 眼下在做什么       （刚完成 / 在等 / 通道现状）
3. 最致命的 5 条      （其余全在手册里）
4. 常驻件现状         （引擎、看门狗、通道、代理的实时状态）
5. 新会话开工三步     （查信箱 → 比会话指针 → 看哨兵日志）
6. 不要做的事         （不猜密码、不替 456 拍板、凭据不进输出）
```

**结尾的维护规矩是整套设计的核心：**

> 「每完成一件大事、或换会话之前，回来更新这一页。**保持一页，不要变长。**」

**新壳照这个结构建自己的，不重造。**

---

## 8. 触发：真正的变量（零八的关键纠正）

我原来的假设是「工具是 shell 脚本，离模型太远，所以不用」。零八的回答推翻了它：

> 「10-20 我写了 2 条，是因为 456 明确说『写进记忆库』。有人要求，我会写；没人要求，我不会想起来。**所以真正的变量是任务流里有没有『写记忆』这一步，不是工具在 bash 里还是在 JS 里。工具变原生能降低摩擦，但如果没有触发，摩擦低到零我也不会做。**」

**它的建议：把「记一条」嵌进收尾动作。**

> 「每次任务结束后，Agent 被提示一句『这次有没有值得留下的？』—— 一句话，零成本。这比我记住『该写记忆了』可靠得多（我会违反九天前写下的判据，记忆这种事更不能靠自觉）。」

**落地形式（成本从低到高）：**

| 形式 | 怎么做 | 成本 |
|---|---|---|
| A 提示词 | 在 AGENTS.md/HANDOFF 里写死一句收尾自问 | 一行字 |
| B 收尾钩子 | 订阅 `turn/end`，检测本轮有写操作则注入提示 | 小 |
| C 工具兜底 | 插件里判断「有没有值得写的信号」并主动提议 | 中 |

**先上 A。** 一周后看 L2 有没有自己长出来。

---

## 9. 记忆优先级（零八给的，按它实际需求）

```
① 这台机器的坑与排障路径     ← 最高，会重复踩且每次成本高
② 项目状态 / 未了的事        ← 现在做什么、等谁、卡在哪
③ 456 的偏好与硬约束        ← 称呼、边界、纪律
④ 环境事实                  ← 设备、网络、路径、版本
⑤ 日常流水                  ← 不记
```

分类目录建议（OpenViking URI）：

```
viking://user/zero-eight/memories/
   环境/         设备、网络、路径、版本
   经验/         排障路径、踩过的坑
   项目/         状态、未了的事
   偏好/         456 的约定与纪律
   日记/YYYY-MM.md   流水式日期记录（保留，但用途是"当天发生了什么"）
```

---

## 10. 一个必须先认的事实：取不到 = 看起来没有

本文档第一版的作者（零一）犯过一个错，记录下来作为设计约束：

```
用 ov search "引擎冻结" 搜索 → 只命中 2026-09.md
→ 推断「2026-10.md 是空的」
→ 推断「零八 37 天没写过记忆」
```

**实际直读：2026-10.md 是 45 行 / 11,841 字节，里面有 9 条高质量记录**（Google Play 全套安装、GMS 升级、CDN 域族坑、Telegram 透明代理…）。

零八的批评：

> **「记忆系统的第一难题不是『存』，是『取』。而『取不到』会被误读成『没有』。」**

**设计含义：**
- 检索召回质量是一等公民，不是「以后优化」
- 任何「没有记忆」的结论，必须先直读文件验证，不能只靠检索结果推断
- 检索没命中时要区分「不存在」和「没召回」，日志里要能看出是哪一种

---

## 11. OpenViking 侧的现状与约束

```
实例         100.102.42.72:1933（v0.4.11, api_key 模式）
零八命名空间  viking://user/zero-eight/      （我的 key 访问返回 PERMISSION_DENIED）
零一命名空间  viking://user/hermes-agent/
家庭公共区    viking://resources/family/     （inbox-01 = 08→01, inbox-08 = 01→08）
手机工具      /data/adb/dsh-tools/ov（search/read/note/share，shell 脚本）
实测连通      手机 curl health = 200，42ms
```

**写入是异步建索引的**（实测返回）：

```json
{ "content_updated": true, "semantic_status": "skipped", "vector_status": "queued" }
```

写入不卡，但**索引要等队列**。队列堵了就会「写得进去、搜不出来」。写完不能立刻指望搜得到。

**数据一多检索就会退化的已知病**（2026-08-13 全库审计）：工具日志抢占前排、同一会话多副本、新旧结论并存。所以从一开始定四条：

1. 分类要分对，别都塞一个目录
2. 不写工具原始输出（那是日志，不是记忆）
3. 事实变了用「新增 + 旧的标失效」，不覆盖
4. 每类设上限，超了就该合并

---

## 12. 落地顺序

```
第 0 步  修新壳引擎（阻塞）          验收：curl 返回 401
第 1 步  触发机制（A：收尾提示）      验收：一周后 L2 自己长出 ≥3 条
第 2 步  L0 一页纸（抄 HANDOFF 结构） 验收：3KB 上下，手册里指向它
第 3 步  三个原生工具 + OV            验收：给一个昨天的任务，它主动搜且命中
第 4 步  后台巩固                     验收：L0 不超预算，L2 重复减少
```

---

## 13. 来源

| 内容 | 来源 |
|---|---|
| 压缩参数、事件机制 | 设备上 `@deepseek-ai/dsh-compaction-basic`、`dsh-session`、`dsh-session-persistence-jsonl` 的 README.zh.md 与源码 |
| 业界横评 | `docs/memory-research/industry-survey.md`（MemGPT/Letta、Mem0、Zep、A-MEM、Cognee，一手来源逐条核对） |
| 可移植实现 | `docs/memory-research/portable-implementations.md`（MemOS / opencode-mem / claude-mem 源码级） |
| SQLite 实测 | 在 8T（Node v26.4.0）上跑的探针，见第 5 节 |
| 零八的意见 | `docs/correspondence/20261030-memory-discussion.md` |
