# Dawn AI Telemetry — 现状梳理与改进文档

> 创建日期：2026-03-23  
> 关联 Issue：[#5 Telemetry: token usage, per-tool histogram, error classification](https://github.com/Supremes/dawn-ai/issues/5)

---

## 一、基础设施概览

Dawn AI 的可观测性基于标准 Spring Boot 生态构建：

```
Spring Boot Actuator
  └── Micrometer (registry abstraction)
        └── micrometer-registry-prometheus
              └── /actuator/prometheus  ←── Prometheus 每 15s 拉取
                                              └── Grafana (http://localhost:3000)
```

### 依赖（pom.xml）

| 依赖 | 用途 |
|------|------|
| `spring-boot-starter-actuator` | 暴露 `/actuator/prometheus` 端点 |
| `micrometer-registry-prometheus` | 指标格式转换 |
| `spring-boot-starter-aop` | `ToolExecutionAspect` 切面织入 |

### 配置（application.yml）

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics
  metrics:
    export:
      prometheus:
        enabled: true
```

---

## 二、改进前现状（约 20% 覆盖）

### 已实现指标

| 指标名 | 类型 | 位置 | 说明 |
|--------|------|------|------|
| `ai.agent.chat.duration` | Timer | `AgentOrchestrator` | 整体 ReAct 请求耗时 |
| `ai.rag.ingestion.total` | Counter | `RagService` | 文档写入向量库总量 |
| `ai.rag.retrieval.total` | Counter | `RagService` | RAG 检索查询总量（无 hit/miss 区分） |
| JVM 自动指标 | 各类 | Micrometer 自动 | 内存、GC、线程、CPU |
| HTTP 请求指标 | 各类 | Spring Boot 自动 | 接口请求量、耗时 |

### 关键缺陷

| 缺陷 | 根因 |
|------|------|
| Tool 执行耗时只写 ThreadLocal，未导出 Prometheus | `ToolExecutionAspect` 缺少 `MeterRegistry` 注入 |
| Token 消耗完全忽略 | `ChatResponse.getMetadata().getUsage()` 从未读取 |
| TaskPlanner 静默降级无感知 | `catch` 块无打点 |
| RAG 只有总量，无命中率 | `retrieve()` 不判断返回结果 |
| 错误无分类统计 | `ApiExceptionHandler` 无打点 |
| Grafana 仅 JVM/HTTP 面板 | 缺少业务可视化 |

---

## 三、改进方案与实现

### M1 — Tool 执行指标（ToolExecutionAspect）

**文件**：`src/main/java/com/dawn/ai/agent/aop/ToolExecutionAspect.java`

**改动**：注入 `MeterRegistry`，在 `proceed()` 之后（成功）和 `catch`（失败）中分别记录指标。

```java
@Around("execution(* com.dawn.ai.agent.tools.*.apply(..))")
public Object captureStep(ProceedingJoinPoint pjp) throws Throwable {
    String toolName = pjp.getTarget().getClass().getSimpleName();
    long start = System.currentTimeMillis();
    String status = "success";
    try {
        Object result = pjp.proceed();
        // ...
        recordMetrics(toolName, status, durationMs);
        return result;
    } catch (Throwable t) {
        status = "error";
        recordMetrics(toolName, status, durationMs);
        throw t;
    }
}

private void recordMetrics(String toolName, String status, long durationMs) {
    meterRegistry.timer("ai.tool.duration", "tool", toolName, "status", status)
            .record(durationMs, TimeUnit.MILLISECONDS);
    meterRegistry.counter("ai.tool.calls.total", "tool", toolName, "status", status)
            .increment();
}
```

**新增指标**：
- `ai.tool.duration{tool, status}` — Timer（Histogram）
- `ai.tool.calls.total{tool, status}` — Counter

---

### M2 — Token 用量（AgentOrchestrator）

**文件**：`src/main/java/com/dawn/ai/agent/AgentOrchestrator.java`

**改动**：将 `.call().content()` 改为 `.call().chatResponse()`，从元数据中读取 Token 用量。

```java
// 改前
String response = chatClient.prompt()...call().content();

// 改后
ChatResponse chatResponse = chatClient.prompt()...call().chatResponse();
String response = chatResponse.getResult().getOutput().getText();
recordTokenUsage(chatResponse);
```

```java
private void recordTokenUsage(ChatResponse chatResponse) {
    Usage usage = chatResponse.getMetadata().getUsage();
    if (usage == null) return;
    Integer inputTokens = usage.getPromptTokens();
    Integer outputTokens = usage.getCompletionTokens();
    if (inputTokens != null && inputTokens > 0) inputTokenCounter.increment(inputTokens);
    if (outputTokens != null && outputTokens > 0) outputTokenCounter.increment(outputTokens);
}
```

> ⚠️ **Spring AI 1.1.2 注意**：`Usage` 接口返回 `Integer`（非 `Long`），方法名为 `getCompletionTokens()`（非 `getGenerationTokens()`）。

**新增指标**：
- `ai.token.input` — Counter（累计输入 Token）
- `ai.token.output` — Counter（累计输出 Token）

---

### M3 — TaskPlanner 成功率（TaskPlanner）

**文件**：`src/main/java/com/dawn/ai/agent/TaskPlanner.java`

**改动**：注入 `MeterRegistry`，在 `plan()` 的成功和 catch 分支各打点一次。

```java
@PostConstruct
void initMetrics() {
    successCounter = Counter.builder("ai.planner.result").tag("status", "success").register(meterRegistry);
    fallbackCounter = Counter.builder("ai.planner.result").tag("status", "fallback").register(meterRegistry);
}

public List<PlanStep> plan(...) {
    try {
        // ...
        successCounter.increment();
        return plan;
    } catch (Exception e) {
        fallbackCounter.increment();
        return Collections.emptyList();
    }
}
```

**新增指标**：
- `ai.planner.result{status=success}` — Counter
- `ai.planner.result{status=fallback}` — Counter

---

### M4 — RAG 命中率（RagService）

**文件**：`src/main/java/com/dawn/ai/service/RagService.java`

**改动**：将原单一 `retrievalCounter` 替换为带 `result` tag 的两个 Counter。

```java
// 改前
retrievalCounter.increment();  // 无区分

// 改后
if (results.isEmpty()) {
    retrievalMissCounter.increment();
} else {
    retrievalHitCounter.increment();
}
```

**指标变更**：
- 移除：`ai.rag.retrieval.total`（无 tag 版本）
- 新增：`ai.rag.retrieval.total{result=hit}` — Counter
- 新增：`ai.rag.retrieval.total{result=miss}` — Counter

---

### M5 — 错误分类（ApiExceptionHandler）

**文件**：`src/main/java/com/dawn/ai/exception/ApiExceptionHandler.java`

**改动**：注入 `MeterRegistry`，每个异常处理方法调用 `recordError(type)`。

```java
private void recordError(String type) {
    meterRegistry.counter("ai.error.total", "type", type).increment();
}
```

| 异常类型 | type 值 |
|----------|---------|
| `AiConfigurationException` | `config_error` |
| `RestClientException`（认证失败） | `llm_auth_error` |
| `RestClientException`（其他） | `llm_error` |
| `HandlerMethodValidationException` | `validation_error` |
| `ConstraintViolationException` | `validation_error` |
| `Exception`（兜底） | `internal_error` |

**新增指标**：
- `ai.error.total{type}` — Counter

---

### M6 — Grafana Dashboard 应用面板

**文件**：`grafana/dashboards/spring-boot.json`

新增 **"AI Application Metrics"** Row，包含 6 个 Panel：

| Panel | 类型 | PromQL |
|-------|------|--------|
| Tool 执行耗时 P95 | TimeSeries | `histogram_quantile(0.95, sum(rate(ai_tool_duration_seconds_bucket[5m])) by (le, tool)) * 1000` |
| Tool 调用率（by tool & status） | TimeSeries | `sum(rate(ai_tool_calls_total[5m])) by (tool, status)` |
| Token 消耗速率 | TimeSeries | `rate(ai_token_input_total[1m])` / `rate(ai_token_output_total[1m])` |
| Planner 成功率 | Stat（带阈值色） | `sum(rate(ai_planner_result_total{status="success"}[5m])) / sum(rate(ai_planner_result_total[5m]))` |
| RAG 命中率 | Stat（带阈值色） | `sum(rate(ai_rag_retrieval_total{result="hit"}[5m])) / sum(rate(ai_rag_retrieval_total[5m]))` |
| 错误分类速率 | TimeSeries（堆叠） | `sum(rate(ai_error_total[5m])) by (type)` |

---

## 四、改进后指标全景

| 指标 | 类型 | Tags | 来源 |
|------|------|------|------|
| `ai.agent.chat.duration` | Timer | `session` | `AgentOrchestrator` |
| `ai.tool.duration` | Timer/Histogram | `tool`, `status` | `ToolExecutionAspect` |
| `ai.tool.calls.total` | Counter | `tool`, `status` | `ToolExecutionAspect` |
| `ai.token.input` | Counter | — | `AgentOrchestrator` |
| `ai.token.output` | Counter | — | `AgentOrchestrator` |
| `ai.planner.result` | Counter | `status` | `TaskPlanner` |
| `ai.rag.ingestion.total` | Counter | — | `RagService` |
| `ai.rag.retrieval.total` | Counter | `result` | `RagService` |
| `ai.error.total` | Counter | `type` | `ApiExceptionHandler` |
| JVM 指标（自动） | 各类 | — | Micrometer |
| HTTP 指标（自动） | 各类 | — | Spring Boot |

---

## 五、遗留工作（后续 Issue）

| 事项 | 说明 |
|------|------|
| Token 指标按 session 聚合 | 目前 `ai.token.*` 无 session tag，加入后可分析单用户成本 |
| Tool 指标语义稳定 | 依赖 Issue #1（ToolRegistry），tool name 应来自注册表而非 `getSimpleName()` |
| RAG as Tool 后命中率语义 | Issue #3 落地后，hit/miss 定义需随之调整 |
| 单元测试覆盖新指标逻辑 | Issue #6（测试覆盖 ≥70%）时补充 Telemetry 相关测试 |
