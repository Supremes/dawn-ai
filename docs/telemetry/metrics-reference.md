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
| `ai.tool.duration` | `ai_tool_duration_seconds` | Timer/Histogram | `tool`, `status` | 单次工具执行耗时 |
| `ai.tool.calls.total` | `ai_tool_calls_total` | Counter | `tool`, `status` | 工具调用总次数 |

**status 取值**：`success` / `error`

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
| `internal_error` | 未捕获的 `Exception` |

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
```
