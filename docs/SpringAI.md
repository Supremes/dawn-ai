advisor 落地

老大，调研完毕。结论先说：**有，且有 3 处明确价值，1 处看似适合但其实不该上**。

## 现状（核实过的事实）

- Spring AI 1.1.4，6 处直接注入 `ChatClient`：`AgentOrchestrator`、`ChatService.simpleChat`、`TaskPlanner`、`MemorySummarizer`、`ReflectionWorker`、`QueryRewriter`、`HydeQueryGenerator`。
- `AiConfig.chatClient()` 只 `defaultSystem(...).build()`，**零 advisor**。
- 当前所有"横切关注点"都是手写的：history 注入/回写（AgentOrchestrator）、token 统计、用户画像/topic/plan 拼 system prompt、HTTP 层日志。

## 推荐落点（按 ROI 排序）

**1. `TokenUsageAdvisor`（自写，最高 ROI）**
现在只有 `AgentOrchestrator.recordTokenUsage()` 在记 token，**TaskPlanner / MemorySummarizer / ReflectionWorker / QueryRewriter / HydeQueryGenerator 这 5 个 ChatClient 调用全部漏计**。把 `inputTokenCounter`/`outputTokenCounter` 移到一个 advisor，在 `chatClient` bean 上 `.defaultAdvisors(new TokenUsageAdvisor(...))` 一次解决。还能按 advisor 的 `adviseContext` 打标签（caller=planner/reflector/...），观察各路径成本。

**2. `MessageChatMemoryAdvisor` 接管会话历史**
`AgentOrchestrator.buildHistory()` + 收尾的两次 `memoryService.addMessage()` 是教科书式的 ChatMemory 用法。把 `MemoryService` 适配成 `ChatMemoryRepository`，advisor 自动注入历史 + 自动保存新轮次。**注意**：当前 `addMessage` 隐含触发 reflection/summarization 事件（看 `MemoryService` 是否在 add 时发事件），advisor 化前要先理清这条副作用链，否则会丢事件。

**3. `SimpleLoggerAdvisor` 或自写一个轻量 Logger Advisor**
现在 HTTP 拦截器只能看到"哪个 base-url、payload 多大"，**看不到"哪个调用方在调"**。Advisor 在逻辑层能抓到 caller 上下文，配合 trace/Langfuse 链路会更干净。优先级低于上面两个，因为 HTTP 日志已基本够用。

## 看似适合但**不推荐**的

**`QuestionAnswerAdvisor` / `RetrievalAugmentationAdvisor`** —— 本项目 RAG 走的是 **Function Tool 路线**（让 LLM 通过 ReAct 自己决定调 `KnowledgeSearchTool`，再加 plan 强约束 + topicId/docId 硬过滤 + dedup），不是 pre-retrieve-then-augment。把它换成 QA Advisor 会丢掉：多轮检索、planner 控制次数、dedup、软/硬过滤兜底。**架构不匹配，别动**。

---

要不要先从 **TokenUsageAdvisor** 开刀？这个改动最小、收益最直接（补齐漏计），不涉及任何业务语义迁移，我可以先出方案等你批准。

---
提问语法 OK，无需纠正。