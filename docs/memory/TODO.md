# Memory TODO

> 来源：基于 `myblog` 中已有的 memory / RAG / context budget 相关内容整理。
> 目标：把散落在 blog 中的设计、实践和问题单，收束成 dawn-ai 可维护的 memory 文档与工程待办。

## P0：先把骨架补齐

| 优先级 | TODO | 说明 | 主要来源 |
|---|---|---|---|
| P0 | 补一篇 memory taxonomy 文档 | 明确定义 Working / Summary / Episodic / Semantic / Procedural / RAG Knowledge 的边界，避免后续“RAG 算不算 memory”反复讨论 | `Agent Development Guide`、`AI-Interview`、`ch08-memory-management-notes.md` |
| P0 | 补一篇 memory lifecycle 文档 | 把写入、窗口裁剪、摘要压缩、固化、反思、召回、衰减串成统一时序图 | `dawn-ai.md`、`agent-memory-architecture-2026-03-10.md`、`dawn-ai-roadmap.md` |
| P0 | 补一篇 reliability / failsafe 文档 | 说明 Redis 不可用、摘要超时、信息抽取幻觉、向量检索无结果时分别如何降级 | `dawn-ai-roadmap.md`、`RAG.md`、`AI-Interview.md` |
| P0 | 补一篇 retrieval 文档 | 独立说明 hybrid retrieval、metadata filter、rerank、evaluation，不要全部塞进架构稿 | `RAG.md`、`ch14-rag-notes.md` |
| P0 | 给现有架构稿补“边界说明” | 在现有架构稿中显式写清楚：用户画像属于 hard memory，RAG 知识库不等于用户长期记忆 | `agent-memory-architecture-2026-03-10.md`、`ch08-memory-management-notes.md` |

## P0：工程能力待落地

| 优先级 | TODO | 对应 issue / 线索 | 说明 |
|---|---|---|---|
| P0 | Summary Buffer 摘要缓冲 | `#23 Memory 增强` | 旧对话不能只 `leftPop` 丢掉，必须先压缩再淘汰 |
| P0 | Memory Consolidation 记忆固化 | `#23 Memory 增强` | 会话结束或用户离线后，将可保留信息转成 embedding / profile 持久化 |
| P0 | Reflection 机制 | `#13 Reflection 机制` | 从碎片情景记忆中提炼高层偏好、长期目标、稳定画像 |
| P0 | Decay / Eviction 机制 | `#23 Memory 增强` | 检索排序不应只看相似度，要加入时间衰减和重要性分数 |
| P0 | User Profile / Hard Memory 存储策略 | `dawn-ai.md`、现有架构稿 | 用户身份、等级、长期偏好应和向量记忆分开存，优先注入 system prompt |
| P0 | Redis failsafe | `#10 MemoryService Redis Failsafe` | Redis 不可用时的读写兜底、降级和恢复策略需要单独定义 |

## P1：把“能跑”提升到“能维护”

| 优先级 | TODO | 说明 | 主要来源 |
|---|---|---|---|
| P1 | 建立 memory 观测指标文档 | 命中率、召回耗时、压缩队列积压、平均相似度、压缩失败率 | 现有架构稿、`RAG.md`、`AI-Interview.md` |
| P1 | 补 context budget / KV Cache 文档 | 解释为什么长上下文会拖慢推理、增大显存、抬高成本 | `KV Cache.md`、现有架构稿、`AI-Interview.md` |
| P1 | 补 memory 与 RAG 的边界案例 | 例如“用户画像”“历史对话摘要”“企业知识库”分别放哪层 | `dawn-ai.md`、`ch08`、`ch14` |
| P1 | 补框架对比文档 | LangChain / LangGraph / Google ADK / AgentScope 在 memory 抽象上的差异 | `ch08-memory-management-notes.md`、`dawn-ai-roadmap.md` |

## P1：检索与效果优化

| 优先级 | TODO | 说明 | 主要来源 |
|---|---|---|---|
| P1 | hybrid retrieval 文档化并工程化 | 明确 dense + sparse + RRF 的策略边界 | `RAG.md` |
| P1 | metadata filter 策略梳理 | 什么时候必须 metadata 过滤，哪些字段可用于裁剪召回范围 | `RAG.md` |
| P1 | rerank 策略说明 | 规则 rerank、cross-encoder、LLM rerank 各自何时使用 | `RAG.md`、`ch14-rag-notes.md` |
| P1 | retrieval 评估基线 | 建立 Recall@K、MRR、NDCG 的统一解释和验收口径 | `RAG.md`、`AI-Interview.md` |
| P1 | HYDE / Agentic RAG 是否纳入 memory 路线 | 需要明确它们是 retrieval 增强，还是 memory 能力的延伸 | `dawn-ai.md`、`dawn-ai-enhancement.md`、`ch14-rag-notes.md` |

## 暂不优先

| 优先级 | TODO | 原因 |
|---|---|---|
| P2 | 继续扩写纯概念型“什么是 memory”入门文 | 当前 blog 中概念储备已够，优先做收束与工程化 |
| P2 | 把所有 RAG 内容直接并入 memory 总文档 | 会让文档边界失焦，后续应拆为独立 retrieval 专题 |

## 施工顺序建议

1. **先补 taxonomy**
2. **再补 lifecycle**
3. **然后拆 retrieval / reliability**
4. **最后补 performance / framework comparison**

这样做的原因很简单：没有边界，就没法稳定命名；没有生命周期，就没法定义失败处理；没有 retrieval 和 reliability，memory 方案就仍然停留在“概念正确但工程上脆弱”。
