# RAG Phase 3 — Iterative Retrieval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Phase 2 基础上，为 `KnowledgeSearchTool` 增加防重复检索、为 `TaskPlanner` 注入多轮检索引导语、为 `AgentOrchestrator` 增加 `ai.rag.calls_per_session` 指标，实现 Agentic RAG Level 3（Iterative Retrieval）。

**Architecture:** 扩展 `StepCollector` 新增 `RETRIEVED_QUERIES` ThreadLocal（与现有 STEPS/COUNTER/MAX_STEPS 同生命周期）。`KnowledgeSearchTool` 在 `apply()` 开头查询并更新该 Set，重复查询直接返回提示。`TaskPlanner.buildPlanPrompt()` 追加 maxRagCalls 引导语。`AgentOrchestrator.doChat()` 在 collect 后统计并 record RAG 调用次数。

**Tech Stack:** Spring AI 1.1.4、Micrometer DistributionSummary、JUnit 5、Mockito、AssertJ

**前置条件：** Phase 2（Task 1-6）已完成，`KnowledgeSearchTool` 已存在于 `tools` 包。

---

## File Map

| 文件 | 操作 | 职责 |
|---|---|---|
| `src/main/resources/application.yml` | Modify | 新增 `app.ai.rag.max-calls-per-session` |
| `src/main/java/com/dawn/ai/agent/StepCollector.java` | Modify | 新增 RETRIEVED_QUERIES ThreadLocal 及其方法 |
| `src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java` | Modify | 新增 dedup 逻辑 + dedupCounter 指标 |
| `src/main/java/com/dawn/ai/agent/plan/TaskPlanner.java` | Modify | buildPlanPrompt 注入 maxRagCalls 引导语 |
| `src/main/java/com/dawn/ai/agent/AgentOrchestrator.java` | Modify | 新增 ai.rag.calls_per_session DistributionSummary |
| `src/test/java/com/dawn/ai/agent/StepCollectorTest.java` | Create | 测试 RETRIEVED_QUERIES 的 init/mark/check/clear |
| `src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolTest.java` | Modify | 追加 dedup 测试用例 |

---

## Task 1：新增配置项

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 在 `app.ai.rag` 节下追加 max-calls-per-session**

当前 `app.ai.rag` 节（Phase 2 完成后）：
```yaml
    rag:
      similarity-threshold: 0.7   # 向量相似度过滤阈值，低于此值的文档被丢弃
      default-top-k: 5            # 最终返回的文档数
      query-rewrite-enabled: true  # 检索前是否用 LLM 改写查询，false 时原样传入
```

修改后：
```yaml
    rag:
      similarity-threshold: 0.7   # 向量相似度过滤阈值，低于此值的文档被丢弃
      default-top-k: 5            # 最终返回的文档数
      query-rewrite-enabled: true  # 检索前是否用 LLM 改写查询，false 时原样传入
      max-calls-per-session: 3    # 每次请求最多 RAG 工具调用次数，独立于 maxSteps
```

- [ ] **Step 2: 验证 YAML 格式**

```bash
./mvnw validate -q
```

Expected: 无报错。

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "config: add app.ai.rag.max-calls-per-session"
```

---

## Task 2：写 StepCollectorTest（TDD Red）

**Files:**
- Create: `src/test/java/com/dawn/ai/agent/StepCollectorTest.java`

- [ ] **Step 1: 创建测试文件**

```java
package com.dawn.ai.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepCollectorTest {

    @BeforeEach
    void setUp() {
        StepCollector.init(10);
    }

    @AfterEach
    void tearDown() {
        StepCollector.clear();
    }

    // ── RETRIEVED_QUERIES 测试 ──────────────────────────────────

    @Test
    @DisplayName("isQueryRetrieved: init 后所有查询均未检索过")
    void isQueryRetrieved_afterInit_returnsFalse() {
        assertThat(StepCollector.isQueryRetrieved("any query")).isFalse();
    }

    @Test
    @DisplayName("markQueryRetrieved + isQueryRetrieved: 标记后应返回 true")
    void markQueryRetrieved_thenIsQueryRetrieved_returnsTrue() {
        StepCollector.markQueryRetrieved("Dawn AI 定价");

        assertThat(StepCollector.isQueryRetrieved("Dawn AI 定价")).isTrue();
    }

    @Test
    @DisplayName("isQueryRetrieved: 未标记的查询应返回 false")
    void isQueryRetrieved_unmarkedQuery_returnsFalse() {
        StepCollector.markQueryRetrieved("query A");

        assertThat(StepCollector.isQueryRetrieved("query B")).isFalse();
    }

    @Test
    @DisplayName("clear: 清理后 isQueryRetrieved 应返回 false")
    void clear_resetsRetrievedQueries() {
        StepCollector.markQueryRetrieved("some query");
        StepCollector.clear();
        StepCollector.init(10); // re-init for afterEach

        assertThat(StepCollector.isQueryRetrieved("some query")).isFalse();
    }

    @Test
    @DisplayName("init: 重新初始化后之前标记的查询应被清除")
    void init_clearsRetrievedQueries() {
        StepCollector.markQueryRetrieved("old query");

        StepCollector.init(10); // re-init

        assertThat(StepCollector.isQueryRetrieved("old query")).isFalse();
    }
}
```

- [ ] **Step 2: 运行确认失败（StepCollector 还没有新方法）**

```bash
./mvnw test -Dtest=StepCollectorTest -q 2>&1 | grep -E "ERROR|cannot find" | head -5
```

Expected: 编译错误，`isQueryRetrieved` / `markQueryRetrieved` 方法不存在。

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/dawn/ai/agent/StepCollectorTest.java
git commit -m "test: add failing StepCollectorTest for RETRIEVED_QUERIES (TDD Red)"
```

---

## Task 3：扩展 StepCollector（TDD Green）

**Files:**
- Modify: `src/main/java/com/dawn/ai/agent/StepCollector.java`

- [ ] **Step 1: 将 StepCollector 替换为以下完整内容**

```java
package com.dawn.ai.agent;

import com.dawn.ai.exception.MaxStepsExceededException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ThreadLocal-based request-scoped step collector.
 * Bridges ToolExecutionAspect and AgentOrchestrator without touching tool classes.
 *
 * Lifecycle per request:
 *   AgentOrchestrator.doChat() calls init() → AOP records steps → collect() → clear()
 *
 * RETRIEVED_QUERIES tracks already-searched queries within one request to prevent
 * duplicate RAG calls that would waste tokens.
 */
@Slf4j
public class StepCollector {

    private static final ThreadLocal<List<AgentStep>> STEPS =
            ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<AtomicInteger> COUNTER =
            ThreadLocal.withInitial(() -> new AtomicInteger(0));
    private static final ThreadLocal<Integer> MAX_STEPS = new ThreadLocal<>();
    /** package-private for test accessibility */
    static final ThreadLocal<Set<String>> RETRIEVED_QUERIES =
            ThreadLocal.withInitial(HashSet::new);

    /** Call at the start of each request to reset state from any previous run. */
    public static void init(Integer maxSteps) {
        STEPS.get().clear();
        COUNTER.get().set(0);
        MAX_STEPS.set(maxSteps);
        RETRIEVED_QUERIES.get().clear();
    }

    /** Called by ToolExecutionAspect after each tool invocation. */
    public static void record(AgentStep step) {
        STEPS.get().add(step);
    }

    /** Returns the next monotonically increasing step number for the current request. */
    public static int getAndIncreaseStepNumber() {
        int next = COUNTER.get().incrementAndGet();
        if (next > MAX_STEPS.get()) {
            log.error("Exceeded Max Steps: {}", next);
            throw new MaxStepsExceededException("Exceeded Max Steps: " + MAX_STEPS.get().toString());
        }
        return next;
    }

    /** Returns a snapshot of all recorded steps for the current request. */
    public static List<AgentStep> collect() {
        return new ArrayList<>(STEPS.get());
    }

    /**
     * Returns true if the given rewritten query has already been searched this request.
     * Used by KnowledgeSearchTool to prevent duplicate RAG calls.
     */
    public static boolean isQueryRetrieved(String query) {
        return RETRIEVED_QUERIES.get().contains(query);
    }

    /**
     * Marks a rewritten query as already searched for this request.
     * Call immediately before executing a RAG retrieval.
     */
    public static void markQueryRetrieved(String query) {
        RETRIEVED_QUERIES.get().add(query);
    }

    /** Must be called in a finally block to prevent ThreadLocal memory leaks. */
    public static void clear() {
        STEPS.remove();
        COUNTER.remove();
        MAX_STEPS.remove();
        RETRIEVED_QUERIES.remove();
    }
}
```

- [ ] **Step 2: 运行 StepCollectorTest，确认全部通过**

```bash
./mvnw test -Dtest=StepCollectorTest -q 2>&1 | tail -10
```

Expected:
```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 3: 运行全套测试，确认无回归**

```bash
./mvnw test -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dawn/ai/agent/StepCollector.java \
        src/test/java/com/dawn/ai/agent/StepCollectorTest.java
git commit -m "feat: extend StepCollector with RETRIEVED_QUERIES for RAG dedup"
```

---

## Task 4：更新 KnowledgeSearchTool（防重复 + 指标）

**Files:**
- Modify: `src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java`
- Modify: `src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolTest.java`

- [ ] **Step 1: 先追加测试用例到 KnowledgeSearchToolTest（TDD Red）**

在现有 `KnowledgeSearchToolTest` 末尾追加以下测试（在最后一个 `}` 前）：

新增 import（追加到现有 import 列表）：
```java
import com.dawn.ai.agent.StepCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
```

修改 `setUp()`，添加 `SimpleMeterRegistry` 并初始化 StepCollector：
```java
private SimpleMeterRegistry meterRegistry;

@BeforeEach
void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    tool = new KnowledgeSearchTool(queryRewriter, ragService, meterRegistry);
    tool.setDefaultTopK(5);
    tool.initMetrics();
    StepCollector.init(10);
}

@AfterEach
void tearDown() {
    StepCollector.clear();
}
```

追加测试方法：
```java
@Test
@DisplayName("apply: 相同改写查询第二次调用时跳过检索并返回提示")
void apply_duplicateQuery_skipsRetrieval() {
    when(queryRewriter.rewrite("月费")).thenReturn("Dawn AI 定价 月费");
    when(ragService.retrieve("Dawn AI 定价 月费", 5)).thenReturn(List.of(new Document("¥99")));

    // 第一次调用 — 正常检索
    tool.apply(new KnowledgeSearchTool.Request("月费"));

    // 第二次相同查询 — 应跳过
    KnowledgeSearchTool.Response secondResponse =
            tool.apply(new KnowledgeSearchTool.Request("月费"));

    assertThat(secondResponse.docsFound()).isEqualTo(0);
    assertThat(secondResponse.context()).contains("已检索过");
    // ragService.retrieve 只被调用了一次（第二次被 dedup 拦截）
    verify(ragService, times(1)).retrieve(anyString(), anyInt());
}

@Test
@DisplayName("apply: 重复查询时 ai.rag.dedup.skipped 计数器 +1")
void apply_duplicateQuery_incrementsDedupCounter() {
    when(queryRewriter.rewrite("test")).thenReturn("test rewritten");
    when(ragService.retrieve("test rewritten", 5)).thenReturn(List.of());

    tool.apply(new KnowledgeSearchTool.Request("test"));
    tool.apply(new KnowledgeSearchTool.Request("test")); // duplicate

    double skipped = meterRegistry.counter("ai.rag.dedup.skipped").count();
    assertThat(skipped).isEqualTo(1.0);
}
```

新增 import（确保这些都在文件顶部）：
```java
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
```

- [ ] **Step 2: 运行确认新测试失败**

```bash
./mvnw test -Dtest=KnowledgeSearchToolTest -q 2>&1 | grep -E "FAIL|ERROR" | head -5
```

Expected: 2 个新测试失败（KnowledgeSearchTool 还没有 dedup 逻辑）。

- [ ] **Step 3: 更新 KnowledgeSearchTool 实现（完整文件）**

```java
package com.dawn.ai.agent.tools;

import com.dawn.ai.agent.StepCollector;
import com.dawn.ai.service.QueryRewriter;
import com.dawn.ai.service.RagService;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * Agent tool that searches the internal knowledge base.
 *
 * Placed in the tools package so ToolRegistry auto-discovers it.
 * ToolExecutionAspect intercepts apply() for step tracing and metrics automatically.
 *
 * Deduplication: uses StepCollector.isQueryRetrieved() to skip identical rewritten
 * queries within the same request, preventing wasted LLM + retrieval calls.
 */
@Slf4j
@Component
@Description("搜索内部知识库，获取与问题相关的背景信息。需要查询产品信息、技术文档或领域知识时调用。")
@RequiredArgsConstructor
public class KnowledgeSearchTool implements Function<KnowledgeSearchTool.Request, KnowledgeSearchTool.Response> {

    private final QueryRewriter queryRewriter;
    private final RagService ragService;
    private final MeterRegistry meterRegistry;

    @Setter
    @Value("${app.ai.rag.default-top-k:5}")
    private int defaultTopK;

    private Counter dedupCounter;

    /** package-private for test access */
    void initMetrics() {
        dedupCounter = Counter.builder("ai.rag.dedup.skipped")
                .description("RAG queries skipped due to deduplication within one request")
                .register(meterRegistry);
    }

    @PostConstruct
    void postConstruct() {
        initMetrics();
    }

    public record Request(@JsonProperty(required = true) String query) {}
    public record Response(String context, int docsFound) {}

    @Override
    public Response apply(Request req) {
        String rewrittenQuery = queryRewriter.rewrite(req.query());

        if (StepCollector.isQueryRetrieved(rewrittenQuery)) {
            dedupCounter.increment();
            log.info("[KnowledgeSearchTool] Skipping duplicate query: {}", rewrittenQuery);
            return new Response("（已检索过相同内容，请换个角度或直接生成回答）", 0);
        }
        StepCollector.markQueryRetrieved(rewrittenQuery);

        List<Document> docs = ragService.retrieve(rewrittenQuery, defaultTopK);

        log.info("[KnowledgeSearchTool] query='{}' → rewritten='{}', docsFound={}",
                req.query(), rewrittenQuery, docs.size());

        return new Response(formatContext(docs), docs.size());
    }

    private String formatContext(List<Document> docs) {
        if (docs.isEmpty()) return "未找到相关知识库内容。";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            sb.append(String.format("[%d] %s\n", i + 1, docs.get(i).getText()));
        }
        return sb.toString();
    }
}
```

注意：测试中通过 `new KnowledgeSearchTool(queryRewriter, ragService, meterRegistry)` 构造，然后调用 `tool.initMetrics()`（package-private）。`@PostConstruct` 方法 `postConstruct()` 在 Spring 容器中调用 `initMetrics()`，测试中手动调用。

- [ ] **Step 4: 更新 KnowledgeSearchToolTest 的 setUp 和 tearDown（确保完整）**

由于 `KnowledgeSearchTool` 现在需要 `MeterRegistry`，Phase 2 的测试需要同步更新。完整 `setUp`/`tearDown`：

```java
private SimpleMeterRegistry meterRegistry;

@BeforeEach
void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    tool = new KnowledgeSearchTool(queryRewriter, ragService, meterRegistry);
    tool.setDefaultTopK(5);
    tool.initMetrics();
    StepCollector.init(10);
}

@AfterEach
void tearDown() {
    StepCollector.clear();
}
```

- [ ] **Step 5: 运行 KnowledgeSearchToolTest，确认全部通过**

```bash
./mvnw test -Dtest=KnowledgeSearchToolTest -q 2>&1 | tail -10
```

Expected:
```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 6: 运行全套测试**

```bash
./mvnw test -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java \
        src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolTest.java
git commit -m "feat: add RAG dedup and ai.rag.dedup.skipped metric to KnowledgeSearchTool"
```

---

## Task 5：更新 TaskPlanner Prompt

**Files:**
- Modify: `src/main/java/com/dawn/ai/agent/plan/TaskPlanner.java`

- [ ] **Step 1: 注入 maxRagCalls 配置**

在 `TaskPlanner` 类中追加字段（在 `parseErrorCounter` 之后）：

```java
@Value("${app.ai.rag.max-calls-per-session:3}")
private int maxRagCalls;
```

- [ ] **Step 2: 更新 `buildPlanPrompt` 方法**

将 `buildPlanPrompt()` 中的 prompt 模板替换为以下内容（追加多轮检索引导语）：

```java
private String buildPlanPrompt(String task,
                               Map<String, String> toolDescriptions,
                               String formatInstructions) {
    String toolList = toolDescriptions.entrySet().stream()
            .map(e -> "- " + e.getKey() + ": " + e.getValue())
            .collect(Collectors.joining("\n"));

    return """
            你是一个任务规划助手。请分析用户的任务，并生成一个 2-5 步的执行计划。

            可用工具：
            %s

            业务约束：
            - action 只能从上方可用工具中选择，最后一步固定为 "finish"
            - reason 使用中文，简短说明为什么要执行该步骤
            - 若单次检索信息不足，可多次调用 knowledgeSearchTool 从不同角度补充，
              直到信息充分再生成最终答案。每次请求最多检索 %d 次。

            用户任务：%s

            %s
            """.formatted(toolList, maxRagCalls, task, formatInstructions);
}
```

- [ ] **Step 3: 运行全套测试（TaskPlannerTest 需保持通过）**

```bash
./mvnw test -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dawn/ai/agent/plan/TaskPlanner.java
git commit -m "feat: inject maxRagCalls guidance into TaskPlanner prompt for iterative retrieval"
```

---

## Task 6：AgentOrchestrator 新增 RAG 调用次数指标

**Files:**
- Modify: `src/main/java/com/dawn/ai/agent/AgentOrchestrator.java`

- [ ] **Step 1: 新增 `ragCallsSummary` 字段（在现有 Counter 字段之后）**

```java
private DistributionSummary ragCallsSummary;
```

新增 import：
```java
import io.micrometer.core.instrument.DistributionSummary;
```

- [ ] **Step 2: 在 `initMetrics()` 中注册指标**

在 `initMetrics()` 末尾追加：
```java
ragCallsSummary = DistributionSummary.builder("ai.rag.calls_per_session")
        .description("Number of knowledgeSearchTool calls per agent session")
        .register(meterRegistry);
```

- [ ] **Step 3: 在 `doChat()` 中记录指标**

在 `List<AgentStep> steps = StepCollector.collect();` 之后追加：

```java
long ragCalls = steps.stream()
        .filter(s -> "KnowledgeSearchTool".equals(s.toolName()))
        .count();
ragCallsSummary.record(ragCalls);
```

- [ ] **Step 4: 运行全套测试**

```bash
./mvnw test -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dawn/ai/agent/AgentOrchestrator.java
git commit -m "feat: add ai.rag.calls_per_session metric to AgentOrchestrator"
```

---

## Task 7：验收检查

- [ ] **Step 1: 运行全套测试**

```bash
./mvnw test -q 2>&1 | tail -15
```

Expected: BUILD SUCCESS，全部通过。

- [ ] **Step 2: 确认 StepCollector 新方法存在**

```bash
grep -n "isQueryRetrieved\|markQueryRetrieved\|RETRIEVED_QUERIES" \
  src/main/java/com/dawn/ai/agent/StepCollector.java
```

Expected: 三个关键词各出现至少 1 次。

- [ ] **Step 3: 确认 Prometheus 指标（需要运行中的应用）**

启动后：
```bash
curl -s http://localhost:8080/actuator/prometheus | grep -E "ai_rag_dedup|ai_rag_calls"
```

Expected 输出包含：
```
ai_rag_dedup_skipped_total
ai_rag_calls_per_session_count
ai_rag_calls_per_session_sum
```

- [ ] **Step 4: 打印最终 git log**

```bash
git log --oneline -8
```

---

## 完成标准

- [ ] `StepCollectorTest` 5 个用例全部 GREEN
- [ ] `KnowledgeSearchToolTest` 5 个用例全部 GREEN（含 2 个 dedup 测试）
- [ ] `./mvnw test` 全套 BUILD SUCCESS
- [ ] `StepCollector` 有 `isQueryRetrieved`、`markQueryRetrieved`、`RETRIEVED_QUERIES` ThreadLocal
- [ ] `KnowledgeSearchTool` 重复查询时返回提示且 `ai.rag.dedup.skipped` +1
- [ ] `TaskPlanner` prompt 包含 maxRagCalls 引导语
- [ ] `AgentOrchestrator` 记录 `ai.rag.calls_per_session` DistributionSummary
