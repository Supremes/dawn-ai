# Memory Taxonomy

## Why taxonomy matters
统一术语以避免不同文档对同一概念的多义解释。对 dawn-ai 来说，明确“memory”与“RAG”边界能稳定设计决策、评估指标与工程实现。

## Layer definitions
以下为在 dawn-ai 语境下的标准术语（稳定表述，括号中为常见别名）：

- Working Memory
  - 定义：当前对话轮次内直接注入 prompt 的短期上下文（LLM context window）。
  - 存储：Redis List / in-memory buffer
  - 易失性：高（随窗口滑动或会话结束丢失）
  - 示例：最近 20 条消息、当前请求的临时工具输出

- Summary Memory
  - 定义：对被弹出或过期短期内容做语义压缩、结构化提取后的表示，用于延长有效上下文寿命。
  - 存储：向量数据库（VectorDB）或文档 DB（带 metadata）
  - 易失性：低（可检索、异步固化）
  - 示例：会话摘要、关键实体 JSON、提炼后的要点

- Episodic Memory
  - 定义：按事件/会话粒度保存的跨会话历史记录，保留行为序列与时间语义（情景记忆）。
  - 存储：向量 DB + 原文归档
  - 易失性：低，按策略衰减或淘汰
  - 示例：用户上一周的会话记录摘要、任务执行轨迹

- Semantic / Hard Memory
  - 定义：稳定事实、用户画像与不可或较少更改的核心信息，优先注入 system prompt（硬记忆）。
  - 存储：结构化数据库（SQL/NoSQL）、KV store
  - 易失性：极低（需要显式更新）
  - 示例：user profile（姓名、权限等级）、长期偏好、企业事实表

- Procedural Memory
  - 定义：Agent 的行为规则、策略、反思后演化的动作模板与工具使用规范（程序化记忆）。
  - 存储：系统提示、策略文件、版本化规则库
  - 易失性：低至中（随模型或策略更新）
  - 示例：任务执行流程、工具调用序列、反思生成的新规则

## Adjacent subsystem: RAG / External Knowledge (not memory)
RAG Knowledge（Retrieval-Augmented Generation 所依赖的检索与知识库）是一个检索/融合子系统，提供按需外部知识检索与证据追溯。它通常使用向量 DB、全文索引、外部文档仓库；但 RAG 本身并不等同于 agent 的“记忆”。

注意：从 RAG 检索到的内容可以被处理（例如去噪、摘要、结构化）并作为 Memory 的派生工件（例如写入 Summary Memory 或被归档到 Episodic Memory）。但这类派生工件的写入与管理使其成为 memory，而非 RAG 本身。

## What belongs to memory vs what belongs to RAG
核心结论：RAG Knowledge 是检索/知识子系统，不计为 Agent 的“记忆”但高度耦合。

- 属于 Memory：任何表示用户状态、会话历史、个性化事实或 Agent 行为规则（Working、Summary、Episodic、Semantic/Hard、Procedural）。这些内容直接承担会话连续性与个性化。
- 属于 RAG：大规模外部知识库、企业文档、通用域知识（不可归属单一用户或会话）。RAG 的职责是按需检索并把结果注入 prompt 或用于生成/扩展 Summary Memory，但检索本身不是记忆的形式。

注：术语“Long-term Memory”在既有文档中广泛出现。为避免歧义，dawn-ai 将其映射为一个历史性/概念性总称：通常包含 Episodic Memory（事件/会话级历史）以及在许多上下文中覆盖 Semantic / Hard Memory 的部分稳定事实（取决于语境和管理策略）。团队文档应优先使用 canonical taxonomy（Episodic / Semantic / Summary 等）并在需要兼容旧稿时注明该映射。

示例：
- user profile → Semantic / Hard Memory
- conversation summary → Summary Memory
- enterprise knowledge base → RAG Knowledge（通过 RAG 检索注入到 prompt；若检索结果被摘要并写入，则成为 Summary Memory 的一个工件）

## Storage and retrieval mapping
| Type | Typical storage | Retrieval trigger | Retrieval semantics |
|---|---:|---|---|
| Working Memory | Redis / in-memory | 每次请求直接注入 | 顺序消息，token-budget sensitive |
| Summary Memory | VectorDB + metadata | 会话窗口溢出 / 定期 consolidation | 语义相似度检索，top-k + rerank |
| Episodic Memory | VectorDB + archive | 任务场景回溯 / 时间过滤 | 时间+语境过滤，事件级检索 |
| Semantic / Hard Memory | SQL / KV | 强制注入 system prompt / profile lookup | 精确匹配或字段查询 |
| Procedural Memory | Versioned policies | 计划执行 / tool selection | 规则优先级 + 模板匹配 |
| (Adjacent subsystem) RAG Knowledge | VectorDB / Search index | On-demand external domain retrieval | 文档级检索，需证据追溯；作为外部知识检索子系统，不直接等同于 agent memory |

## Common misclassifications
- 把 RAG 当作“长期记忆”本身：错误。RAG 提供检索能力，长期记忆是需要被写入、维护并与用户/会话绑定的数据集合。
- 把 Summary Memory 与 Semantic Memory 混淆：前者是压缩/可检索的会话表示，后者是稳定事实。
- 忽视 Procedural Memory：把行为规则散在 system prompt，会导致无法版本化与审计。

## Recommended dawn-ai terminology
- 固定术语：Working Memory、Summary Memory、Episodic Memory、Semantic / Hard Memory、Procedural Memory
- 邻近子系统：RAG Knowledge（Retrieval / External Knowledge，非记忆但紧密协作）
- 别名处理：在文档初次出现处标注常见别名（如 Summary Memory = 摘要缓冲 / summary buffer；Semantic / Hard Memory = 用户画像 / profile）。

---

（文档短小，便于引用在 README 与架构稿前置）
