# Telemetry 指标参考手册

> 适用版本：Spring AI 1.1.2 + Micrometer + Prometheus

---

## 指标命名规范

采用 `ai.<component>.<metric>` 三段式命名，与 Micrometer 规范一致（Prometheus 展示时 `.` → `_`）。

---

## 完整指标清单

### 请求级别

| 指标（Micrometer 名） | Prometheus 名 | 类型 | Tags | 说明 |
|---|---|---|---|---|
| `ai.agent.chat.duration` | `ai_agent_chat_duration_seconds` | Timer/Histogram | `session` | 完整 ReAct 请求生命周期 |

### Tool 级别

| 指标 | Prometheus 名 | 类型 | Tags | 说明 |
|---|---|---|---|---|
| `ai.tool.duration` | `ai_tool_duration_seconds` | Timer/Histogram | `tool`, `status`, `outcome` | 单次工具执行耗时 |
| `ai.tool.calls.total` | `ai_tool_calls_total` | Counter | `tool`, `status`, `outcome` | 工具调用总次数 |
| `ai.tool.retries.total` | `ai_tool_retries_total` | Counter | `tool` | 安全自动重试次数（resilience4j Retry `onRetry` 事件；仅 `RETRYABLE_FAILURE` 且 `safeToRetry()` 的结果或 `BulkheadFullException` 会触发） |

**status 取值**：`success` / `error`
**outcome 取值**：`ToolOutcomeStatus.stepStatus()`（如 `success` / `empty` / `retryable_failure` / `permanent_failure` / `refused`）

**Prometheus 查询示例**：
```promql
# WeatherTool P99 耗时
histogram_quantile(0.99, rate(ai_tool_duration_seconds_bucket{tool="WeatherTool"}[5m]))

# CalculatorTool 错误率
rate(ai_tool_calls_total{tool="CalculatorTool", status="error"}[5m])
/ rate(ai_tool_calls_total{tool="CalculatorTool"}[5m])
```

### Token 级别

| 指标 | Prometheus 名 | 类型 | Tags | 说明 |
|---|---|---|---|---|
| `ai.token.input` | `ai_token_input_total` | Counter | — | LLM 输入 Token 累计 |
| `ai.token.output` | `ai_token_output_total` | Counter | — | LLM 输出 Token 累计 |

> **数据来源**：`ChatResponse.getMetadata().getUsage().getPromptTokens()` / `getCompletionTokens()`  
> **注意**：Spring AI ReAct 循环内部可能有多次 LLM 调用，当前只记录最终一次的 usage。

**成本估算示例**（gpt-4o-mini 单价）：
```promql
# 每分钟输入成本（美元）：$0.15/1M tokens
rate(ai_token_input_total[1m]) * 0.15 / 1000000 * 60
```

### 规划级别

| 指标 | Prometheus 名 | 类型 | Tags | 说明 |
|---|---|---|---|---|
| `ai.planner.result` | `ai_planner_result_total` | Counter | `status` | TaskPlanner 规划结果 |

**status 取值**：`success` / `fallback`

### RAG 级别

| 指标 | Prometheus 名 | 类型 | Tags | 说明 |
|---|---|---|---|---|
| `ai.rag.ingestion.total` | `ai_rag_ingestion_total` | Counter | — | 文档写入向量库总数 |
| `ai.rag.retrieval.total` | `ai_rag_retrieval_total` | Counter | `result` | RAG 检索次数（hit/miss） |

**result 取值**：`hit`（返回非空结果）/ `miss`（返回空结果）

**命中率查询**：
```promql
sum(rate(ai_rag_retrieval_total{result="hit"}[5m]))
/ sum(rate(ai_rag_retrieval_total[5m]))
```

### 错误级别

| 指标 | Prometheus 名 | 类型 | Tags | 说明 |
|---|---|---|---|---|
| `ai.error.total` | `ai_error_total` | Counter | `type` | API 错误分类总量 |

**type 取值**：

| 值 | 触发场景 |
|----|---------|
| `config_error` | `AiConfigurationException` — API Key 未配置 |
| `llm_error` | `RestClientException` — OpenAI 网络/服务异常 |
| `llm_auth_error` | `RestClientException` + `HttpRetryException` — 认证失败 |
| `validation_error` | `ConstraintViolationException` / `HandlerMethodValidationException` |
| `llm_circuit_open` | `CallNotPermittedException`（直接或被包裹在 cause 链中）— LLM 熔断器 OPEN，请求在发起网络调用前被拒绝，返回 HTTP 503 |
| `internal_error` | 未捕获的 `Exception` |

### Resilience4j 级别（自动导出）

由 `resilience4j-spring-boot3` + `resilience4j-micrometer` 自动注册，无需手动打点。
实例名：熔断器 `llmChat` / `llmEmbedding`（`LlmCircuitBreakerSupport` 按请求路径选择）；
Retry / Bulkhead 按工具类名命名（`WebTool` / `BashTool` / `DispatchSubAgentTool` / …，由 `ToolExecutionAspect` 按需创建）。

| 指标 | Prometheus 名 | 类型 | Tags | 说明 |
|---|---|---|---|---|
| `resilience4j.retry.calls` | `resilience4j_retry_calls_total` | Counter | `name`, `kind` | 按结果分类的调用数；`kind` = `successful_without_retry` / `successful_with_retry` / `failed_without_retry` / `failed_with_retry` |
| `resilience4j.circuitbreaker.state` | `resilience4j_circuitbreaker_state` | Gauge | `name`, `state` | 当前状态为 1，其余为 0；`state` = `closed` / `open` / `half_open` / `disabled` / `forced_open` / `metrics_only` |
| `resilience4j.circuitbreaker.calls` | `resilience4j_circuitbreaker_calls_seconds` | Timer | `name`, `kind` | 已记录调用；`kind` = `successful` / `failed` / `ignored` |
| `resilience4j.circuitbreaker.not.permitted.calls` | `resilience4j_circuitbreaker_not_permitted_calls_total` | Counter | `name` | 熔断 OPEN 期间被拒绝（快速失败）的调用 |
| `resilience4j.circuitbreaker.failure.rate` | `resilience4j_circuitbreaker_failure_rate` | Gauge | `name` | 滑动窗口内失败率（%），窗口未满时为 -1 |
| `resilience4j.circuitbreaker.buffered.calls` | `resilience4j_circuitbreaker_buffered_calls` | Gauge | `name`, `kind` | 滑动窗口内的调用数（`successful` / `failed`） |
| `resilience4j.circuitbreaker.slow.calls` | `resilience4j_circuitbreaker_slow_calls` | Gauge | `name`, `kind` | 慢调用数（LLM 流式请求本来就长，慢调用熔断靠 `slow-call-duration-threshold=20m` 实际关闭；`slow-call-rate-threshold=100` 是 `>=` 阈值，单独设 100 并不能关闭） |
| `resilience4j.circuitbreaker.slow.call.rate` | `resilience4j_circuitbreaker_slow_call_rate` | Gauge | `name` | 慢调用率（%） |
| `resilience4j.bulkhead.available.concurrent.calls` | `resilience4j_bulkhead_available_concurrent_calls` | Gauge | `name` | 当前剩余并发许可 |
| `resilience4j.bulkhead.max.allowed.concurrent.calls` | `resilience4j_bulkhead_max_allowed_concurrent_calls` | Gauge | `name` | 配置的最大并发（WebTool=8 / BashTool=4 / DispatchSubAgentTool=4 / 其余 32） |

**熔断失败判定**（`LlmCircuitBreakerSupport`）：传输层异常（IOException / ResourceAccessException / 超时）、HTTP 429、5xx。
其它 4xx（鉴权 / 参数错误）记为成功，不会打开熔断。路由规则：`/embeddings` → `llmEmbedding`；仅 `/rerank` 等显式列出的非 LLM 路径放行（不受熔断保护），其余一律 `llmChat`。

**Prometheus 查询示例**：
```promql
# llmChat 熔断器当前是否 OPEN
resilience4j_circuitbreaker_state{name="llmChat",state="open"} == 1

# 熔断期间每秒被拒绝的 LLM 请求
rate(resilience4j_circuitbreaker_not_permitted_calls_total{name="llmChat"}[5m])

# WebTool 剩余并发许可（接近 0 说明 Bulkhead 快满）
resilience4j_bulkhead_available_concurrent_calls{name="WebTool"}

# 各工具重试后仍失败的比例
sum(rate(resilience4j_retry_calls_total{kind="failed_with_retry"}[5m])) by (name)
```

**Actuator 端点**：`/actuator/circuitbreakers`、`/actuator/circuitbreakerevents`、`/actuator/retries`、`/actuator/retryevents`、`/actuator/bulkheads`、`/actuator/bulkheadevents`。
状态切换（CLOSED → OPEN → HALF_OPEN）与 Retry 耗尽、Bulkhead 拒绝都会以 `[Resilience]` 前缀记 WARN 日志（`ResilienceConfig`）。

---

## SSE 错误码

`/api/chat/stream` 在 `event: error` 中携带的 `code` 取值：

| code | 触发场景 | 产生位置 |
|---|---|---|
| `TIMEOUT` | SSE 超过 `app.ai.stream.timeout-ms` 未完成 | `ChatService` |
| `CAPACITY_EXCEEDED` | `chatStreamExecutor` 队列已满，请求被拒绝 | `ChatService` |
| `MAX_STEPS_EXCEEDED` | ReAct 步数超过 `app.ai.react.max-steps` | `AgentOrchestrator` |
| `AI_NOT_CONFIGURED` | API Key / base-url 未配置 | `AgentOrchestrator` |
| `LLM_UNAVAILABLE` | **新增**：`llmChat` / `llmEmbedding` 熔断器 OPEN，请求在发起网络调用前快速失败（`CallNotPermittedException`）。默认 30s 后进入 HALF_OPEN 放行 2 个探测请求，成功即恢复 | `AgentOrchestrator` / `ChatService` |
| `INTERNAL_ERROR` | 其它未分类异常 | `AgentOrchestrator` / `ChatService` |

同步接口（`/api/chat` 等）遇到熔断 OPEN 时由 `ApiExceptionHandler` 返回 HTTP 503，并记 `ai_error_total{type="llm_circuit_open"}`。

---

## 自动采集指标（参考）

由 Spring Boot Actuator + Micrometer 自动采集，无需手动配置：

| 指标前缀 | 说明 |
|---------|------|
| `jvm_memory_*` | JVM 堆/非堆内存 |
| `jvm_gc_*` | GC 暂停时间 |
| `jvm_threads_*` | 线程数（live/daemon/peak） |
| `system_cpu_usage` | 系统 CPU |
| `process_cpu_usage` | 进程 CPU |
| `http_server_requests_*` | HTTP 请求量 / 耗时（按 uri, method, status 分组） |
| `process_uptime_seconds` | 进程运行时长 |

---

## 常用告警规则（Prometheus AlertManager）

```yaml
groups:
  - name: dawn-ai
    rules:
      - alert: HighToolErrorRate
        expr: |
          sum(rate(ai_tool_calls_total{status="error"}[5m]))
          / sum(rate(ai_tool_calls_total[5m])) > 0.1
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Tool error rate > 10%"

      - alert: LowRAGHitRate
        expr: |
          sum(rate(ai_rag_retrieval_total{result="hit"}[10m]))
          / sum(rate(ai_rag_retrieval_total[10m])) < 0.5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "RAG hit rate < 50%, consider enriching knowledge base"

      - alert: PlannerFallbackHigh
        expr: |
          sum(rate(ai_planner_result_total{status="fallback"}[5m]))
          / sum(rate(ai_planner_result_total[5m])) > 0.2
        for: 3m
        labels:
          severity: warning
        annotations:
          summary: "TaskPlanner fallback rate > 20%"

      - alert: LlmCircuitOpen
        expr: |
          max(resilience4j_circuitbreaker_state{name=~"llmChat|llmEmbedding",state="open"}) == 1
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "LLM circuit breaker is OPEN — upstream provider failing, requests are being rejected with LLM_UNAVAILABLE / 503"
```
