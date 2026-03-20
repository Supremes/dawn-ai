# Dawn AI — Next Action Plan

> 创建日期：2026-03-20
> 更新日期：2026-03-20（Mentor Agent 深度评审后修订）
> 基准版本：`feature/react-redesign`（commit `8012f58`）
> GitHub Issues：https://github.com/Supremes/dawn-ai/issues

---

## 现状诊断

### 原有结构性缺陷

| # | 问题 | 代码位置 | 严重性 |
|---|---|---|---|
| 1 | 工具列表硬编码在 Orchestrator | `AgentOrchestrator:83` | 高 |
| 2 | 无 Streaming，全部阻塞式响应 | `ChatService:50` | 高 |
| 3 | RAG 与 Agent 完全割裂，无法按需检索 | `ChatService:43-49` | 高 |
| 4 | `maxSteps` 只是提示词约束，无代码硬限制 | `AgentOrchestrator:73` | 中 |
| 5 | Telemetry 覆盖极薄，Token 用量/工具级指标缺失 | 多处 | 中 |
| 6 | 零测试覆盖 | — | 中 |
| 7 | 结构化输出依赖提示词软约束 + `extractJson()` 防御性解析 | `TaskPlanner:96-103` | 中 |

### Mentor 评审新发现的问题

| # | 问题 | 代码位置 | 严重性 |
|---|---|---|---|
| B1 | RAG context 拼入 userMessage 后存入 Redis，**污染后续对话历史** | `ChatService:43-48` | 高 |
| B2 | `markPlanStepsCompleted` 用 Set 比对，无法区分顺序和多次调用 | `AgentOrchestrator:144-152` | 中 |
| B3 | `simpleChat` 绕过所有 Agent 基础设施（无 session/工具/metrics） | `ChatService:64-71` | 中 |
| B4 | `MemoryService` Redis 不可用时无 Failsafe，可能导致 `doChat()` 整体失败 | `AgentOrchestrator:109-122` | 中 |

> Bug B1 不需要新建 Issue，**在 Action 3（RAG as a Tool）里一并修复**：删除 ChatService 预处理逻辑后，该 Bug 自然消除。

---

## Action 列表

### Action 7 — Structured Output 硬约束（P0，最先做）

**GitHub Issue**：[#7](https://github.com/Supremes/dawn-ai/issues/7)

**问题**：`TaskPlanner` 依赖提示词软约束，并用 `extractJson()` 做防御性解析兜底，是持续在生产中制造风险的定时炸弹。

**软约束 vs 硬约束对比**：

```
软约束（当前）:
  Prompt: "只返回 JSON 数组，不要其他文字"
  → LLM 可能返回 markdown 代码块 + 多余文字
  → extractJson() 勉强解析
  → 字段类型错误在运行时 NPE，fallback 掩盖真实解析失败

硬约束（目标）:
  API 层注入 JSON Schema
  → OpenAI 在采样阶段约束 token 合法性
  → 返回结果 100% 符合 Schema，直接反序列化
```

**方案（Spring AI BeanOutputConverter）**：

```java
BeanOutputConverter<List<PlanStep>> converter =
    new BeanOutputConverter<>(new ParameterizedTypeReference<List<PlanStep>>() {});

String response = chatClient.prompt()
    .user(prompt + converter.getFormat())  // 自动注入 JSON Schema
    .call()
    .content();

List<PlanStep> plan = converter.convert(response);
```

**改造后可删除**：`TaskPlanner.extractJson()` 方法、prompt 中的手写格式说明。

---

### Action 1 — 工具动态注册（P0）

**GitHub Issue**：[#1](https://github.com/Supremes/dawn-ai/issues/1)

**问题**：`AgentOrchestrator` 和 `TaskPlanner` 中工具名称与描述全部硬编码，新增工具必须修改 Orchestrator。当前存在**双重维护税**：`toolNames()` 列表与 `getToolDescriptions()` Map 完全割裂，Bean 是否真实存在在运行时才能发现。

**方案**：
- 新增 `ToolRegistry`，启动时自动扫描符合条件的 Tool Bean，收集 name + description
- `AgentOrchestrator` 改为 `toolNames(toolRegistry.getNames())`
- `TaskPlanner` 改为 `toolRegistry.getDescriptions()`

**效果**：新增 Tool Bean 后，Orchestrator 和 Planner 自动感知，零改动。

---

### Action 2 — Streaming SSE（P0）

**GitHub Issue**：[#2](https://github.com/Supremes/dawn-ai/issues/2)

**问题**：当前全部阻塞等待 LLM 生成完毕，用户体验差。

**注意**：Streaming 模式下 `Flux` 里的异常处理方式与同步不同，需在 Action 4（maxSteps 硬限制）的 `MaxStepsExceededException` 处理中同步考虑。

**方案**：
- 新增 `GET /api/v1/chat/stream` endpoint，返回 `Flux<ServerSentEvent<String>>`
- `AgentOrchestrator` 新增 `streamChat()` 使用 `.stream().content()`
- 原 `POST /api/v1/chat` 保持不变

---

### Action 3 — RAG as a Tool / Agentic RAG Level 1（P1）

**GitHub Issue**：[#3](https://github.com/Supremes/dawn-ai/issues/3)

**问题**：RAG 在 `ChatService` 层作为预处理强制执行，且存在**历史消息污染 Bug**：RAG context 被拼入 `userMessage` 后存入 Redis，下一轮对话 LLM 会看到被 RAG context 填充的"历史用户消息"，污染对话上下文。

**方案**：
- 新增 `KnowledgeSearchTool`，封装 `RagService.buildContext()`，注册为 Spring Bean
- **完全删除** `ChatService` 中的 `ragEnabled` 预处理逻辑（同时消除 Bug B1）
- 删除 `ChatRequest.ragEnabled` 字段
- Agent 在 ReAct 循环中自主决定何时调用知识库工具

**与 Agentic RAG 的关系**：

```
Agentic RAG 完整形态（层次递进）：
  Level 1: RAG as a Tool       ← 本 Action，Agent 决定"要不要"检索
  Level 2: Query Rewriting       Agent 先改写查询再检索
  Level 3: Iterative Retrieval   多轮检索直到信息充分
  Level 4: Self-RAG              带自我反思的检索决策
```

**ReAct 循环示意（改造后）**：
```
User: "Dawn AI 月费是多少？算年费"
LLM Reason: 需查知识库
LLM Action: knowledgeSearchTool("Dawn AI 月费")
Observe:    [月费 ¥99...]
LLM Reason: 已知月费，执行计算
LLM Action: calculatorTool("99 * 12")
Observe:    1188
LLM: 年费为 ¥1188
```

---

### Action 4 — maxSteps 硬限制（P1）

**GitHub Issue**：[#4](https://github.com/Supremes/dawn-ai/issues/4)

**问题**：`maxSteps` 只通过提示词告知 LLM，若 LLM 忽视该指令，可能无限循环调用工具，成本失控。

**实现位置修正**（Mentor 评审意见）：不在 `ToolExecutionAspect` 里注入 `maxSteps`，而是通过 `StepCollector.init(int maxSteps)` 让 Collector 自身负责边界检查——Aspect 保持纯粹的观测职责，边界控制收拢到 Collector。

```java
// AgentOrchestrator:
StepCollector.init(maxSteps);

// StepCollector:
public static void init(int maxSteps) {
    STEPS.get().clear();
    COUNTER.get().set(0);
    MAX_STEPS.set(maxSteps);
}

// 在 nextStepNumber() 或 record() 中检查：
if (COUNTER.get().get() >= MAX_STEPS.get()) {
    throw new MaxStepsExceededException(MAX_STEPS.get());
}
```

---

### Action 5 — Telemetry 补全（P1）

**GitHub Issue**：[#5](https://github.com/Supremes/dawn-ai/issues/5)

**现状**：

```java
// 已有（覆盖极薄）
Timer("ai.agent.chat.duration")        // AgentOrchestrator — 仅整体耗时
Counter("ai.rag.ingestion.total")      // RagService — 仅次数
Counter("ai.rag.retrieval.total")      // RagService — 仅次数
// ToolExecutionAspect 的工具耗时只写入 StepCollector（内存），重启即丢
```

**缺失指标及补全方案**：

| 指标 | 实现位置 | 说明 |
|---|---|---|
| `ai.tool.duration{tool=...}` | `ToolExecutionAspect` | 工具级 Histogram，按工具名 tag，持久化到 Prometheus |
| `ai.token.input` / `ai.token.output` | `AgentOrchestrator` | 从 `ChatResponse.getMetadata().getUsage()` 提取 |
| `ai.planner.result{status=success/fallback}` | `TaskPlanner` | Planner 成功率 |
| `ai.rag.retrieval.result{result=hit/miss}` | `RagService` | RAG 命中率 |
| `ai.error.total{type=...}` | `ApiExceptionHandler` | 错误分类统计 |
| `ai.plan.alignment{result=matched/unmatched}` | `AgentOrchestrator` | Plan-Execution 对齐率（配合 B2 修复） |

> 依赖 Action 1（需要 tool tag 语义稳定）和 Action 3（需要 RAG hit/miss 语义）完成后再做。

---

### Action 6 — 补全单元测试与集成测试（P2）

**GitHub Issue**：[#6](https://github.com/Supremes/dawn-ai/issues/6)

**测试策略**：

| 测试类 | 重点 |
|---|---|
| `AgentOrchestratorTest` | Mock ChatClient，验证 plan 注入、steps 收集、ThreadLocal 清理 |
| `TaskPlannerTest` | BeanOutputConverter 正确解析、LLM 格式错误时明确报错（非静默 fallback） |
| `RagServiceTest` | Mock VectorStore，验证 retrieve 和 buildContext |
| `StepCollectorTest` | ThreadLocal 多线程隔离、maxSteps 触发异常 |
| `ToolExecutionAspectTest` | AOP 拦截后 AgentStep 正确记录，工具异常时步骤编号一致性 |
| `ChatControllerTest` | MockMvc API 契约验证 |

**目标**：核心业务类行覆盖率 ≥ 70%，`mvn test` 全部通过。

---

### Bug Fix 1 — markPlanStepsCompleted 语义修正

**GitHub Issue**：[#8](https://github.com/Supremes/dawn-ai/issues/8)

**问题**：当前用 `Set<String>` 判断工具是否被调用，无法区分调用顺序和多次调用，Plan-Execution 对齐只有形式没有实质。

**方案**：改为按顺序逐步匹配，记录每个 PlanStep 对应的实际 AgentStep 引用，供 Telemetry 使用。

---

### Bug Fix 2 — simpleChat 使用范围明确化

**GitHub Issue**：[#9](https://github.com/Supremes/dawn-ai/issues/9)

**问题**：`ChatService.simpleChat()` 完全绕过 `AgentOrchestrator`、`ToolExecutionAspect`、`MemoryService`，是一个无 session/工具/metrics 的裸 LLM 调用，使用场景不明确。

---

### Bug Fix 3 — MemoryService Redis Failsafe

**GitHub Issue**：[#10](https://github.com/Supremes/dawn-ai/issues/10)

**问题**：Redis 不可用时 `buildHistory()` 行为未验证，可能导致整个 `doChat()` 失败，需要确认并加入 Failsafe（返回空历史而非抛异常）。

---

## 执行顺序（修订版）

```
Day 1-2:  Action 7  — BeanOutputConverter（独立，消除 extractJson 定时炸弹）

Day 3-5:  Action 1  — ToolRegistry
          Bug Fix 1 — markPlanStepsCompleted 语义修正（顺带做，代码量小）

Week 2:   Action 3  — KnowledgeSearchTool（依赖 Action 1；同时消除 Bug B1 RAG 历史污染）
          Action 4  — maxSteps 硬限制（StepCollector.init(maxSteps) 方案）

Week 3:   Action 2  — Streaming SSE（核心逻辑稳定后更安全）
          Action 5  — Telemetry 补全（依赖 Action 1 + Action 3 tag 语义稳定）
          Bug Fix 2 — simpleChat 使用范围明确化
          Bug Fix 3 — MemoryService Redis Failsafe

Week 4:   Action 6  — 补测试（覆盖所有新增逻辑）
```

---

## 依赖关系图

```
Action 7（BeanOutputConverter）  ← 独立，最先做
Action 1（ToolRegistry）         ← 其他 Action 的基础
    ├── Action 3（RAG as Tool）  ← 强依赖 Action 1
    ├── Action 4（maxSteps）     ← 弱依赖 Action 1（共用 StepCollector）
    └── Action 5（Telemetry）    ← 依赖 Action 1 + Action 3 的 tag 语义

Action 2（Streaming SSE）        ← 独立，但放在核心逻辑稳定后更安全
    └── 影响 Action 4 的异常处理设计（Flux 里 catch 方式不同）

Action 6（测试）                 ← 最后做，覆盖所有新增逻辑
```

---

## "高级 Java 开发"到"AI Agent 架构师"缺失的核心能力

当前项目已实现 ReAct 骨架，但距离 Agent 架构师视角还差以下四个维度：

### 1. 语义失败治理（Reliability Engineering）

传统后端失败是确定性的（NPE、超时），Agent 系统有独特的**语义失败**：LLM 返回 200 OK、JSON 解析成功、工具也执行了，但最终答案是错的。当前项目完全没有检测机制。需要：
- Plan Verification（计划执行结果与预期是否一致）
- Output Validation（工具返回值合理性校验）
- Conversation Drift Detection（多轮后 Agent 是否跑偏）

### 2. 成本治理（Cost Governance）

每次对话调用两次 LLM（Planner + ReAct loop），ReAct 内部每轮工具调用都把完整历史重新发给 LLM，Token 用量随对话轮数**二次方增长**。Telemetry 是基础，上层还需要：
- Token Budget（每次请求预算上限）
- Context Window Management（历史消息滑动窗口裁剪）
- Model Routing（简单任务用低成本模型）

### 3. 多 Agent 协作（Multi-Agent Orchestration）

单 Orchestrator 在工具超过 10 个后会遇到 LLM 注意力稀释问题。需要把专业工具分组，由专门的 Sub-Agent 负责，主 Agent 做任务路由——类比从单体服务到微服务的架构演进。

### 4. Durable Execution（状态持久化与恢复）

`StepCollector` 是纯内存 ThreadLocal，进程重启后进行中的任务完全丢失，无法异步化长耗时任务。需要 Agent 中间状态持久化（Redis-based checkpoint 或 Temporal Workflow）。

---

## Spring AI ReAct 机制说明

Spring AI 的 `.toolNames().call()` 内部已经是一个隐式的 ReAct 循环：

```
chatClient.prompt().toolNames(...).call()  ← 这一行内部是循环

内部逻辑（伪代码）:
while (true) {
    response = LLM(messages + tool_schemas)
    if (response 是纯文本) return response
    if (response 是 tool_call) {
        result = 执行工具(tool_call)
        messages.append(tool_call + result)  // Observe
        // 继续循环，LLM 看到 Observation 后再 Reason
    }
}
```

本项目在此基础上叠加了：
- **循环前**：`TaskPlanner` 独立 LLM 调用，生成计划引导推理方向
- **循环中**：`ToolExecutionAspect` AOP 透明拦截，记录每次 Action + Observe
