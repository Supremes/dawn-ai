# Multi-Agent 设计共识

> 通过 `/grill-me` 与老大对齐后沉淀。所有关键决策点均经过对照评估，
> 选定方案以"复用现有抽象、改动局部可回滚"为优先原则。

## 1. 动机：上下文窗口隔离

引入 multi-agent 不是为了"角色扮演"，而是解决**单 Agent 上下文随 plan / skill / RAG 结果膨胀**的根本痛点。

主 Agent 把"深度检索 / 长文档分析"这类**脏活**派给运行在隔离上下文的子 Agent；子 Agent 跑完只把摘要带回，主对话上下文不被污染。

> 业界对照：Claude Code 的 Task tool、OpenAI Swarm 的 handoff，都是同一类模式。

## 2. 接口形态：作为 Tool

`DispatchSubAgentTool` 暴露为普通工具，主 Agent LLM 在 ReAct 循环里自主决定何时派发。

- 复用 `ToolRegistry` / `ToolExecutionAspect` / `StepCollector`
- 不改 `TaskPlanner`，不引入 Supervisor 层
- 调试路径与现有工具完全一致

## 3. 类型模型：预定义多类型，MVP 仅一种

```
SubAgentRegistry (auto-discover SubAgentDefinition beans)
   └── research  (MVP 唯一注册)
```

- `SubAgentDefinition` 是值对象 record，按 `@Bean` 注册
- 接口预留 `subagentType` 字段——未来新增类型是**加法**，不重构
- MVP 只落 `research`：深度检索 + 综合

## 4. 能力边界：工具白名单 + 禁递归

| Sub-agent type | 允许工具 | maxSteps | timeout |
|---|---|---|---|
| `research` | `knowledgeSearchTool`、`loadSkillTool`、`readSkillResourceTool` | 15 | 60s |

- 工具白名单显式声明，主 Agent 的 `weatherTool` / `calculatorTool` / `DispatchSubAgentTool` 均不下发
- **禁止递归**：sub-agent 拿不到 `DispatchSubAgentTool`，调用栈最多 2 层（主 → sub）

## 5. 状态共享：完全隔离

| 维度 | sub-agent 行为 |
|---|---|
| sessionId | 继承主 sessionId 仅用于 Langfuse / 日志关联，不读写业务状态 |
| 对话历史 | **不读**——主 Agent 必须在 `taskDescription` 自包含 |
| Memory | **不写**——避免污染 importance 衰减/consolidation 体系 |
| `StepCollectorContext` | 独立 detached context，步骤计数与主 Agent 完全隔离 |
| 返回值 | 纯函数 `SubAgentResult`，含 `summary` + `subSteps`（仅观测） |

## 6. 执行模式：同步阻塞

- 主 Agent 调 `DispatchSubAgentTool` → 阻塞等结果 → tool result 回 ReAct 循环
- sub-agent 跑在专用 `subAgentExecutor` 线程池（core=2 / max=8 / queue=16 / AbortPolicy）
- 复用 `CompletableFuture.get(timeoutSec, SECONDS)` 强制超时
- 超时后**放弃** future（不强行 interrupt），从 sub context 收割已完成的步骤拼 partial summary

> 未来要并行（多 sub-agent 同时跑）：包装层加并发，sub-agent 内核不动。

## 7. SSE 协议扩展：进度心跳 + 最终聚合

### 事件序列

```
connected
  ↓
plan_thinking* → plan? → thinking*
  ↓
step* | sub_progress*    ← sub_progress 在 sub-agent 内部每完成一步即冒泡
  ↓
token*
  ↓
done | error
```

### 关键改动

- `AgentStep` 加 `status`（`done`/`error`）+ `subSteps`（仅 dispatch 步骤填充）
- 保留 5 参 delegating 构造器，所有旧调用点零破坏
- `SubStepProvider` 接口（`agent.trace` 包内）让 `ToolExecutionAspect` 自动透传 `subSteps`，避免 `agent.trace` 反向依赖 tools 子包
- `ChatStreamEvent.subProgress(sessionId, parentToolName, subAgentType, subStep, currentTool)` 工厂
- `StreamSinkHolder` ThreadLocal（`sse` 包）让 `DispatchSubAgentTool` 在流式模式下拿到 sink，为 sub-agent 构造进度回调

前端不渲染 `sub_progress` 时退化为只看最终 `step`，协议向后兼容。

## 8. 失败处理：软失败 + partial result

| 失败类型 | 处理 | 主 Agent 看到 |
|---|---|---|
| `MaxStepsExceededException`（步数超限） | catch → PARTIAL_SUCCESS | 已完成步骤拼接摘要 + "达到步数上限" |
| 总超时（>60s） | future.cancel(false) → PARTIAL_SUCCESS | 已完成步骤拼接摘要 + "超时" |
| `LLMProviderException` / 其他 RuntimeException | catch → PARTIAL_SUCCESS（若有已完成步）/ FAILED（无 partial） | 错误原因 |
| `AiConfigurationException`（不可恢复） | **直接抛出**，不吞 | 主 Agent 也挂，向外抛 |
| 线程池饱和（`RejectedExecutionException`） | catch → FAILED | "sub-agent 线程池已满" |

Partial summary 直接拼接 sub-agent 已完成步骤的 input/output（截断 200 字符），不再发 LLM 调用——避免失败时再花 token 与再失败的风险。

## 9. 资源边界

| 维度 | 默认值 | 配置键 |
|---|---|---|
| 单次主对话最多派 sub-agent 次数 | 3 | `app.ai.subagent.max-dispatches-per-session` |
| research sub-agent 内部 maxSteps | 15 | `app.ai.subagent.research.max-steps` |
| research sub-agent 总超时 (秒) | 60 | `app.ai.subagent.research.timeout-seconds` |
| 模型 | 同主 Agent | `app.ai.subagent.research.model-override` |
| 温度 | 同主 Agent | `app.ai.subagent.research.temperature-override` |

派发上限通过 `DispatchSubAgentTool` 内部 count 主 collector 中已有派发步骤数实现，达到上限直接返回 REFUSED 字符串（不抛异常），让 LLM 看见边界自主收敛。

## 10. 可观测性

### Prometheus

- `ai.subagent.dispatches{type, status}` — 累计派发次数（按 SUCCESS/PARTIAL_SUCCESS/FAILED 分桶）
- `ai.subagent.duration{type, status}` — 派发耗时分布
- `ai.subagent.steps{type}` — 单次派发内部步数分布

命名与现有 `ai.tool.*` / `ai.rag.*` / `ai.planner.*` 对齐。

### Langfuse

- sub-agent 的 LLM Span 自动归到主对话 Session（通过 `AiInteractionContext.wrap` 把 sessionId 显式传播到 worker 线程，`LangfuseObservationConfig` 的 ObservationFilter 在 worker 上读到 sessionId）
- 不单独配置 `parent_span_id`：OTel 默认上下文传播 + session.id 已足够 Langfuse 把 sub-agent trace 归到正确会话

## 11. 实现拓扑

```
com.dawn.ai.agent.subagent/
  ├── SubAgentDefinition.java          值对象（type / prompt / tools / 资源边界）
  ├── SubAgentExecutionStatus.java     SUCCESS / PARTIAL_SUCCESS / FAILED
  ├── SubAgentResult.java              纯函数返回值
  ├── SubAgentExecutor.java            接口
  ├── SubAgentRegistry.java            自动收集所有 Definition bean
  └── GenericReActSubAgentExecutor.java 通用 ReAct 执行内核

com.dawn.ai.agent.tools/
  └── DispatchSubAgentTool.java        主 Agent 看到的入口工具，被 ToolRegistry 自动发现

com.dawn.ai.agent.trace/
  ├── AgentStep.java                   + status + subSteps 字段
  └── SubStepProvider.java             让 ToolExecutionAspect 自动透传 subSteps

com.dawn.ai.config/
  └── SubAgentConfig.java              注册 research definition + subAgentExecutor 线程池

com.dawn.ai.sse/
  ├── ChatStreamEvent.java             + subProgress(...) 工厂
  └── StreamSinkHolder.java            流式期间持有 SSE sink 的 ThreadLocal
```

## 不在范围内（明确不做）

- **角色专业化**（Supervisor + 多 Worker）——MVP 不做，单 Agent 派发已能覆盖核心痛点
- **能力域拆分**（按工具集分多 Agent）——当前工具数不足以驱动这个改造
- **并行派发**——MVP 同步阻塞；扩展点已在 `CompletableFuture` 层留好，未来加并发不动 sub-agent 内核
- **Feature flag**——YAGNI，配置默认值即开即用
- **递归 dispatch**——sub-agent 拿不到 `DispatchSubAgentTool`，硬约束防止失控
- **自动重试**——业务无关重试容易掩盖真实问题；失败信号交给主 Agent LLM 自主决策

## 验收

见 [verification.md](./verification.md)。
