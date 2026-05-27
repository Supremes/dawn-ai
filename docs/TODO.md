---
updated: 2026-05-23 20:34
---
1. System Prompt 动态组装 #issue22
2. Multi-Agent #issue28
3. RAG 中文检索优化 + 引入 Hyde 召回率优化 #issue36
4. 引入 Agent 评估系统 #issue26
5. 支持 Skill 和 MCP 协议 #issue25
6. 支持更多的 tools 

## Issues

```
[MemoryAccessUpdater] Failed to update lastAccessedAt: PreparedStatementCallback; bad SQL grammar [UPDATE vector_store SET metadata = jsonb_set(metadata, '{lastAccessedAt}', to_jsonb(?::bigint))
```

## 有价值的待落地点

- HITL(Human in the loop): 在 loop 的每个生命周期内，做出可插拔的 hook 点，进行介入，使得 agent 能够按照预期运行：
	- PreTurn：修改注入模型的上下文、注入 system 提醒、做权限检查，
	- MidTurn：审批 tool call，修改参数、拦截危险操作
	- PostTurn：在本轮 turn 结束，下一轮 turn 开始前触发。审查 observation、支持 loop、修改要传给下一个 turn 的内容
- Skill

- agent paradigm



老大，结论：**dawn-ai 已经落地了 5 个 Agent 范式，最值得继续落地的是 Evaluator/Critic、Router/Supervisor、Workflow Agent 和 Human-in-the-loop；暂时不建议做“全自动长程自治 Agent / swarm”。**

从第一性原理看，Agent 范式本质上是在回答 5 个问题：**谁决定下一步、能用什么工具、状态怎么保存、结果怎么验证、失败怎么收敛**。

| 范式                               | dawn-ai 当前状态 |   是否建议落地 | 判断                                                         |
| ---------------------------------- | ---------------: | -------------: | ------------------------------------------------------------ |
| **Direct LLM / Chat Agent**        |             已有 |         已落地 | `ChatService` / 基础问答能力                                 |
| **Tool-use Agent**                 |             已有 |         已落地 | `ToolRegistry` 自动发现 `CalculatorTool`、`WeatherTool`、`KnowledgeSearchTool` |
| **ReAct Agent**                    |             已有 |         已落地 | `AgentOrchestrator` 通过 Spring AI `.toolNames()` 执行工具循环，并用 `StepCollector` 追踪步骤 |
| **Plan-and-Execute Agent**         |             已有 | 已落地但可增强 | `TaskPlanner` 先生成计划，再注入 system prompt；下一步可做计划执行一致性校验 |
| **RAG Agent / Agentic RAG**        |             已有 | 已落地且是主线 | `RagService` 已有 rewrite、HyDE、hybrid search、rerank、RRF  |
| **Memory Agent**                   |             已有 | 已落地但可增强 | Redis 短期记忆 + summary/reflection 长期记忆已经具备雏形     |
| **Skill-based Agent**              |             已有 |   很适合继续做 | 已有 `SkillLoader`、`LoadSkillTool`、`ReadSkillResourceTool`，适合发展成可插拔能力体系 |
| **Evaluator / Critic Agent**       |       未完整成型 |   **强烈建议** | 用来检查回答是否引用足够上下文、是否跳过工具、是否幻觉；非常适合 RAG 场景 |
| **Router / Supervisor Agent**      |         部分已有 |       **建议** | 现在有 `RetrievalRouter`，但还可以扩展到任务级路由：闲聊、RAG、工具、记忆、skill、诊断 |
| **Workflow / State-machine Agent** |           未成型 |       **建议** | 比 ReAct 更可控，适合 ingest、检索评测、长任务、诊断流程     |
| **Human-in-the-loop Agent**        |           未成型 |   建议有限落地 | 对高风险操作、知识库写入、生产配置变更加确认门槛             |
| **Multi-agent Collaboration**      |           未成型 |           谨慎 | 可以做 Supervisor + 专家子 agent，不建议一开始做多个 agent 自由对话 |
| **Autonomous Long-running Agent**  |           未成型 |       暂不建议 | 需要任务队列、权限、审计、暂停/恢复、幂等等基础设施，否则风险高 |
| **Swarm / Debate Agent**           |           未成型 |         不建议 | 成本高、可控性差，对当前产品价值不直接                       |

我会按优先级这样落地：

1. **Evaluator/Critic Agent**：对 RAG 回答做后验检查，例如“有没有检索上下文”“答案是否基于证据”“是否需要二次检索”。这是当前收益最高的补强。
2. **Router/Supervisor Agent**：把用户请求先分类，再决定走普通 chat、RAG、skill、memory、diagnostics 或工具调用，避免所有任务都塞进一个 ReAct loop。
3. **Workflow Agent**：把 ingest、retrieval evaluation、memory consolidation 这类固定流程从“模型自由决定”改成显式状态机，提升稳定性。
4. **Skill Agent 体系增强**：现在已经有 skill loader，可以继续做 skill 匹配策略、skill 版本、resource 权限、调用审计。
5. **Human-in-the-loop**：知识库写入、删除、生产配置、外部副作用工具调用前要求确认。

不建议现在做的，是**长程自治 Agent**和**swarm**。dawn-ai 现在的核心价值是“可观测、可控、可评测的 Java AI Agent 应用”，不是开放式 AutoGPT。先把 **控制面、评测面、路由面**打牢，比堆多个 agent 更符合当前代码结构。

