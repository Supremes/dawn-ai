# Multi-Agent 验收手册

> 本分支不提供自动化 UT，请按以下 3 个场景在本地 / 测试环境逐项验证。
> 每个场景跑通才视为 MVP 完成。失败 / 部分场景必须截图存档。

## 0. 环境准备

```bash
# 1. 启动依赖
docker compose up -d

# 2. 配置 LLM（参考 .env.example）
export OPENAI_API_KEY=...
export BASE_URL=...
export CHAT_MODEL=...

# 3. （可选）覆盖 sub-agent 默认值用于调试
export APP_AI_SUBAGENT_RESEARCH_TIMEOUT_SECONDS=60
export APP_AI_SUBAGENT_MAX_DISPATCHES_PER_SESSION=3

# 4. 启动应用
mvn spring-boot:run

# 5. 知识库预热（至少灌一个长文档以便深度调研有内容可检索）
curl -X POST http://localhost:8080/api/v1/rag/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "content": "<贴入一段足够长的、有结构的内容用于场景 A 调研，至少 2000 字>",
    "source": "rag-evolution-doc",
    "category": "design"
  }'
```

## 1. 启动期自检

启动日志应当包含：

```
[SubAgentRegistry] init complete: 1 sub-agent type(s) registered: [research]
[SubAgentConfig] registered research sub-agent: maxSteps=15, timeout=60s, model=(default)
[ToolRegistry] Registered tool: dispatchSubAgentTool — 把需要长时间检索…
[ToolRegistry] Discovery complete. N tool(s) registered: […, dispatchSubAgentTool]
```

任一缺失即配置错误，先排查。

---

## 场景 A：主 Agent 应当派 sub-agent（深度调研）

### 触发

```bash
curl -N -H "Accept: text/event-stream" \
  -X POST http://localhost:8080/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "message": "帮我深度调研一下 dawn-ai 项目里 RAG 是怎么演进的，列出关键决策和原因。",
    "sessionId": "verify-A"
  }' | tee /tmp/verify-A.sse
```

### 预期 SSE 事件序列（按时间序）

1. `event: connected`
2. `event: plan_thinking` / `event: plan`（planner 可能产出含 `dispatchSubAgentTool` 的计划）
3. `event: step` —— 可能先有一次普通 `knowledgeSearchTool`（主 Agent 探一下）
4. `event: step` —— `data.toolName="DispatchSubAgentTool"`, `data.status="done"`
   - **关键**：在该 step 完成前，应看到多个 `event: sub_progress` 事件，`data.subAgentType="research"`、`data.subStep` 单调递增
   - 该 step 的 `data.subSteps` 数组应至少包含 3 个元素
5. `event: token` 多次
6. `event: done` —— `data.totalSteps` 含 dispatch 步骤；`data.answer` 含结构化结论

### 判定标准

| 项 | 通过条件 |
|---|---|
| `DispatchSubAgentTool` 出现 | ≥ 1 次 |
| `sub_progress` 出现 | ≥ 3 次（证明心跳工作） |
| `data.subSteps.length` | ≥ 3 |
| 最终 `data.answer` | 结构化、引用了知识库内容、可读 |
| 总耗时 | < 60s（未触发超时） |

### 服务端旁证

```bash
# Grafana / Prometheus
curl -s http://localhost:8080/actuator/prometheus | grep ai_subagent
# 应当看到：
#   ai_subagent_dispatches_total{type="research",status="SUCCESS"} 1.0
#   ai_subagent_duration_seconds_sum{...}
#   ai_subagent_steps_count{type="research"} 1.0
```

应用日志应有：

```
[DispatchSubAgentTool] dispatching: type=research, parentSession=verify-A, dispatchedSoFar=0, taskChars=...
[SubAgent:research] parentSession=verify-A, status=SUCCESS, steps=<N>, durationMs=<...>
```

### 截图存档

- 完整 SSE 流（截图 /tmp/verify-A.sse 关键片段）→ `docs/multi-agent/screenshots/scenario-A-sse.png`
- Grafana `ai.subagent.dispatches` 指标变化 → `docs/multi-agent/screenshots/scenario-A-metrics.png`
- Langfuse Session `verify-A` 的 trace 列表（应同时看到主 Agent 和 sub-agent 的 LLM span）→ `docs/multi-agent/screenshots/scenario-A-langfuse.png`

---

## 场景 B：主 Agent 不应派 sub-agent（简单问题）

### 触发

```bash
curl -N -H "Accept: text/event-stream" \
  -X POST http://localhost:8080/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "message": "今天北京的天气怎么样？",
    "sessionId": "verify-B"
  }' | tee /tmp/verify-B.sse
```

### 预期

- `event: step` 中应**只**出现 `data.toolName="WeatherTool"`（或类似）
- **不应**出现任何 `data.toolName="DispatchSubAgentTool"` 的 step
- **不应**出现任何 `event: sub_progress`

### 判定标准

| 项 | 通过条件 |
|---|---|
| `DispatchSubAgentTool` 出现 | 0 次 |
| `sub_progress` 出现 | 0 次 |
| Prometheus `ai_subagent_dispatches_total` | 与场景 A 后保持不变 |

### 失败信号（需修系统提示）

如果主 Agent 错误地派了 sub-agent：

- 系统提示中"❌ 不要派"准则未被 LLM 遵守
- 解决：调整 `AgentOrchestrator.formatSubAgents()` 中的判断准则措辞，或降低 sub-agent 在 prompt 中的"诱惑性"

### 截图存档

- SSE 流截图 → `docs/multi-agent/screenshots/scenario-B-sse.png`

---

## 场景 C：软失败路径（手动注入超时）

### 触发

```bash
# 1. 临时把 sub-agent 超时压到 5s（覆盖默认 60s）
export APP_AI_SUBAGENT_RESEARCH_TIMEOUT_SECONDS=5

# 2. 重启应用
mvn spring-boot:run

# 3. 发一个肯定查不完的调研任务
curl -N -H "Accept: text/event-stream" \
  -X POST http://localhost:8080/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{
    "message": "请深入调研 dawn-ai 所有模块的设计演进，分模块给出完整时间线、关键 commit、设计动机，并且对每个模块都做 5 轮以上多角度知识库检索。",
    "sessionId": "verify-C"
  }' | tee /tmp/verify-C.sse
```

### 预期

- 出现至少 1-2 个 `sub_progress` 事件后超时
- `event: step` 中 `data.toolName="DispatchSubAgentTool"` 的 `data.subSteps` 非空（有部分完成步骤）
- 该 step 在 SSE 流里的 output 文本中应可见 "（子 Agent 部分完成，原因：sub-agent 执行超时 (5s)）" 字样
- 主 Agent **不崩溃**，继续基于 partial 摘要生成最终回答
- `event: done` 正常发出，`data.answer` 中应明确告诉用户"基于部分检索信息"

### 服务端旁证

```bash
curl -s http://localhost:8080/actuator/prometheus | grep ai_subagent_dispatches_total
# 应当看到：
#   ai_subagent_dispatches_total{type="research",status="PARTIAL_SUCCESS"} 1.0
```

应用日志：

```
[SubAgent:research] parentSession=verify-C, status=PARTIAL_SUCCESS, reason=timeout, steps=<N>, durationMs=≈5000
```

### 判定标准

| 项 | 通过条件 |
|---|---|
| sub-agent status | PARTIAL_SUCCESS |
| 主 Agent 是否崩溃 | 否，正常发出 `done` 事件 |
| 用户能否得到回答 | 是，回答中包含"部分信息"说明 |
| Prometheus 中 PARTIAL_SUCCESS 计数 | +1 |

### 截图存档

- SSE 流（突出 sub_progress 后超时 + 主 Agent 继续）→ `docs/multi-agent/screenshots/scenario-C-sse.png`
- Prometheus PARTIAL_SUCCESS 计数 → `docs/multi-agent/screenshots/scenario-C-metrics.png`
- 应用日志 timeout 告警 → `docs/multi-agent/screenshots/scenario-C-log.png`

### 收尾

```bash
# 验证完恢复默认超时
unset APP_AI_SUBAGENT_RESEARCH_TIMEOUT_SECONDS
```

---

## 验收清单

| 场景 | 通过 | 截图存档 | 备注 |
|---|---|---|---|
| 0. 启动期自检 | ☐ | / | |
| A. 应派且成功 | ☐ | ☐ ☐ ☐ | |
| B. 不应派 | ☐ | ☐ | |
| C. 软失败 partial | ☐ | ☐ ☐ ☐ | |

**3 个行为场景全部通过 + 截图齐全 → multi-agent MVP 完成**。

## 常见故障排查

| 现象 | 可能原因 | 排查 |
|---|---|---|
| `ToolRegistry` 没注册 `dispatchSubAgentTool` | 工具类未被 Spring 扫描 | 检查 `DispatchSubAgentTool` 是否带 `@Component` 且位于 `com.dawn.ai.agent.tools` 包 |
| `SubAgentRegistry` 为空 | `SubAgentConfig` 未生效 | 检查 `@Configuration` + `@Bean` 注解、`@Value` 默认值是否正确解析 |
| 主 Agent 永远不派 sub-agent | 系统提示判断准则太严格 / LLM 模型太弱 | 调整 `formatSubAgents()` 文案；或换更强的模型测试 |
| 主 Agent 派得太频繁 | 系统提示诱惑过强 | 加强"不要派"的准则；降低判断准则在 prompt 中的权重 |
| sub-agent 步骤未在 SSE 中冒泡 | `StreamSinkHolder.get()` 返回 null | 确认 `AgentOrchestrator.streamChat` 已调用 `StreamSinkHolder.set(sink)` |
| Langfuse 中 sub-agent span 没归入主 Session | `AiInteractionContext.wrap` 没生效 | 确认 worker 线程上 `AiInteractionContext.getSessionId()` 非空 |
