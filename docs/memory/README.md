# Memory 文档总览

> 整理日期：2026-04-26
> 范围：`myblog` 中与 **Agent / LLM memory** 强相关的内容。
> 不纳入范围：JVM / OS / MySQL / Redis 等泛“内存”话题，除非它们直接用于解释 Agent memory。

## 结论先行

当前 blog 里和 memory 相关的内容已经形成了 5 条主线，但分散在学习路线、设计笔记、roadmap、RAG 实践和面试题中：

1. **Memory 不是聊天记录**：已经明确区分了短期记忆、长期记忆、用户画像、情景记忆、程序记忆。
2. **上下文窗口是预算，不是存储**：滑动窗口只能止血，真正可用的方案必须有 token budgeting、summary buffer、按需检索。
3. **Memory 有完整生命周期**：写入、压缩、固化、反思、召回、遗忘，这条链路在 blog 中已基本成型。
4. **RAG 与 memory 强耦合但不等价**：RAG 更偏“外部知识检索”，memory 更偏“会话连续性 + 用户状态 + 历史经验”。
5. **缺的不是概念，而是成体系文档**：当前 `docs/memory/` 已有架构稿，但还没有把边界、生命周期、失败处理、评估方法整理成可维护文档集。

## 当前已沉淀到 dawn-ai 的内容

| 文档 | 定位 | 备注 |
|---|---|---|
| `agent-memory-architecture-2026-03-10.md` | 当前最完整的 memory 架构稿 | 已覆盖 context window 上限、Working / Summary / Long-term 分层、用户画像、RAG 回注、基础可观测性 |

## myblog 中可复用的 memory 相关材料

| 主题 | 核心内容 | 主要来源（myblog） | 对 dawn-ai 的价值 |
|---|---|---|---|
| 概念入口与学习主线 | 把向量数据库定义为“外部记忆库”，把 RAG 定义为给 LLM 动态补记忆的方式 | `source/_posts/AI/AI Agent.md` | 适合做目录首页里的“为什么需要 memory”导读 |
| 分层记忆体系 | Working / Session / Long-term / Knowledge 分层，含 Redis、Vector DB、Token Budget、摘要压缩 | `source/_posts/AI/Agent Development Guide.md` | 适合抽成“memory taxonomy + layered architecture”专题 |
| dawn-ai 当前 memory 方案草稿 | Summary Buffer、Consolidation、Decay / Eviction、核心记忆、短期记忆、Reflection | `source/_posts/AI/dawn-ai.md` | 这是最贴近项目现状的 memory 设计备忘录 |
| dawn-ai 增强清单 | 明确把 Memory Management、RAG、向量检索算法归到“知识与记忆层”，并标出已有 / 待补充项 | `source/_posts/AI/dawn-ai-enhancement.md` | 适合转成 docs 中的“能力地图 / 完整度说明” |
| dawn-ai roadmap 与 issue 映射 | 把上下文预算、Summary Buffer、Reflection、Memory 增强、Redis failsafe 与 issue 编号关联起来 | `source/_posts/AI/dawn-ai-roadmap.md` | 适合做“文档到工程 issue 的映射” |
| Memory 基础理论 | 短期 / 长期双层记忆、语义 / 情景 / 程序记忆、ADK / LangGraph / LangChain 对比 | `articles_no_render/Agentic Design Patterns/ch08-memory-management-notes.md` | 适合做“概念边界”与“框架对照”专题 |
| RAG 作为长期记忆检索面 | 向量检索、BM25、混合召回、metadata filter、rerank、评估指标、Agentic RAG | `source/_posts/AI/RAG.md`、`articles_no_render/Agentic Design Patterns/ch14-rag-notes.md` | 适合拆出“memory retrieval plane”专题，而不是混进纯架构文 |
| Memory 问答化素材 | ChatMemory、MessageWindow / TokenWindow、Memory 类型、上下文窗口突破方式、FAQ 式表达 | `source/_posts/AI/AI-Interview.md` | 适合做 FAQ / onboarding 文档 |
| 上下文成本与底层性能 | KV Cache 为什么加速、为什么吃显存、为什么长上下文会把成本推高 | `source/_posts/AI/KV Cache.md` | 适合作为“context budget”附录，支撑为什么必须压缩与检索 |

## 已经比较清晰的统一认知

### 1. memory 的边界

- **Working Memory**：当前轮次对话上下文，直接进入 prompt。
- **Summary Memory**：对旧对话做压缩后的可携带表示，用来延长有效上下文寿命。
- **Long-term / Episodic Memory**：跨会话保留的历史事件、行为、对话摘要，通常落向量库。
- **Semantic / Hard Memory**：用户画像、偏好、核心事实，适合结构化存储，并优先注入 system prompt。
- **Procedural Memory**：Agent 的行为规则、工具使用策略、反思结果。
- **RAG Knowledge**：偏“外部知识库检索”，不是用户个体记忆，但在实现上常复用相同检索基础设施。

### 2. memory 的生命周期

blog 中已经隐含出一条比较完整的链路：

`写入短期记忆 -> 触发裁剪 / Token Budget -> 生成摘要 -> 固化到长期记忆 -> 反思提升 -> 检索召回 -> 衰减 / 淘汰`

缺少的是把这条链路写成一份独立文档，并补齐触发条件、失败兜底、数据结构、观测指标。

### 3. 当前最缺的不是“再讲一遍原理”，而是把散点收束成专题

目前内容的主要问题不是空白，而是：

- **概念散**：taxonomy 分布在 `Agent Development Guide`、`AI-Interview`、`ch08`。
- **工程链路散**：Summary Buffer / Reflection / Decay / Redis failsafe 分布在 `dawn-ai*` 多篇文章里。
- **RAG 与 memory 边界散**：RAG.md 很强，但与 memory 文档之间还没有明确分工。
- **可运维内容散**：评估、监控、降级、性能分散在 `RAG.md`、`AI-Interview`、roadmap 和现有架构稿中。

## 对 dawn-ai 文档目录的建议拆分

| 建议专题 | 应承接的内容 |
|---|---|
| `README.md` | 总览、术语边界、阅读顺序、文件索引 |
| `agent-memory-architecture-2026-03-10.md` | 保留为架构总图与主叙事文档 |
| `memory-taxonomy-*.md` | Working / Summary / Episodic / Semantic / Procedural / RAG 的边界 |
| `memory-lifecycle-*.md` | 写入、压缩、固化、反思、召回、遗忘的时序与触发机制 |
| `memory-retrieval-*.md` | 混合检索、metadata filter、rerank、评估指标 |
| `memory-reliability-*.md` | Redis failsafe、摘要失败、提取幻觉、降级与恢复 |
| `memory-performance-*.md` | token budgeting、Prompt Cache / KV Cache、成本与延迟 |

## 推荐阅读顺序

1. `agent-memory-architecture-2026-03-10.md`
2. `source/_posts/AI/Agent Development Guide.md` 的 Phase 2 / Phase 3
3. `source/_posts/AI/dawn-ai.md`
4. `source/_posts/AI/dawn-ai-roadmap.md`
5. `source/_posts/AI/RAG.md`
6. `articles_no_render/Agentic Design Patterns/ch08-memory-management-notes.md`
7. `source/_posts/AI/KV Cache.md`

## 直接结论

如果只看当前材料，dawn-ai 的 memory 方向已经有了足够明确的设计骨架：

- **架构主轴**：短期记忆 + 摘要压缩 + 长期记忆 + 用户画像
- **关键增强点**：Reflection、Decay / Eviction、混合检索、可观测性、失败兜底
- **最值得优先补齐的文档空白**：taxonomy、lifecycle、retrieval、reliability

后续施工请直接参考同目录下的 `TODO.md`。
