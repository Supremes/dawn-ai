# RAG Phase 2 — Agentic RAG Level 1-2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 RAG 从 ChatService 强制预处理改造为 Agent 自主调用的工具，新增 QueryRewriter 自动改写查询，消除 Bug B1（RAG context 历史污染）。

**Architecture:** 新增 `QueryRewriter`（@Service，BeanOutputConverter 输出）和 `KnowledgeSearchTool`（@Component，放入 tools 包自动被 ToolRegistry 发现）。删除 `ChatService` 的 RAG 预处理逻辑和 `ChatRequest.ragEnabled` 字段。Spring AI ReAct 循环自动感知新工具，无需改 AgentOrchestrator。

**Tech Stack:** Spring AI 1.1.4、BeanOutputConverter、Mockito、JUnit 5、AssertJ

---

## File Map

| 文件 | 操作 | 职责 |
|---|---|---|
| `src/main/resources/application.yml` | Modify | 新增 `app.ai.rag.query-rewrite-enabled` |
| `src/main/java/com/dawn/ai/service/QueryRewriter.java` | Create | 改写用户查询为向量检索友好的语义短语 |
| `src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java` | Create | 封装 QueryRewriter + RagService，注册为 Agent 工具 |
| `src/main/java/com/dawn/ai/service/ChatService.java` | Modify | 删除 RAG 预处理逻辑 |
| `src/main/java/com/dawn/ai/dto/ChatRequest.java` | Modify | 删除 `ragEnabled` 字段 |
| `src/test/java/com/dawn/ai/service/QueryRewriterTest.java` | Create | 测试改写逻辑（disabled bypass、chatClient 调用） |
| `src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolTest.java` | Create | 测试工具调用链（rewrite → retrieve → format） |

---

## Task 1：新增配置项

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 在 `app.ai.rag` 节下追加配置**

当前 `app.ai.rag` 节（Phase 1 已有）：
```yaml
    rag:
      similarity-threshold: 0.7   # 向量相似度过滤阈值，低于此值的文档被丢弃
      default-top-k: 5            # 最终返回的文档数
```

修改后：
```yaml
    rag:
      similarity-threshold: 0.7   # 向量相似度过滤阈值，低于此值的文档被丢弃
      default-top-k: 5            # 最终返回的文档数
      query-rewrite-enabled: true  # 检索前是否用 LLM 改写查询，false 时原样传入
```

- [ ] **Step 2: 验证 YAML 格式**

```bash
./mvnw validate -q
```

Expected: 无报错。

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "config: add app.ai.rag.query-rewrite-enabled"
```

---

## Task 2：写 QueryRewriterTest（TDD Red）

**Files:**
- Create: `src/test/java/com/dawn/ai/service/QueryRewriterTest.java`

- [ ] **Step 1: 创建测试文件**

```java
package com.dawn.ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryRewriterTest {

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;

    private QueryRewriter queryRewriter;

    @BeforeEach
    void setUp() {
        queryRewriter = new QueryRewriter(chatClient);
    }

    @Test
    @DisplayName("rewrite: queryRewriteEnabled=false 时直接返回原始查询，不调用 LLM")
    void rewrite_disabled_returnsOriginalQuery() {
        queryRewriter.setQueryRewriteEnabled(false);

        String result = queryRewriter.rewrite("月费多少");

        assertThat(result).isEqualTo("月费多少");
        verify(chatClient, never()).prompt();
    }

    @Test
    @DisplayName("rewrite: queryRewriteEnabled=true 时调用 LLM 并返回改写后的查询")
    void rewrite_enabled_returnsRewrittenQuery() {
        queryRewriter.setQueryRewriteEnabled(true);

        // BeanOutputConverter 将解析此 JSON，提取 rewrittenQuery 字段
        String llmResponse = "{\"rewrittenQuery\": \"Dawn AI 定价 月费 价格\"}";
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(llmResponse);

        String result = queryRewriter.rewrite("月费多少");

        assertThat(result).isEqualTo("Dawn AI 定价 月费 价格");
        verify(chatClient).prompt();
    }

    @Test
    @DisplayName("rewrite: 改写时将原始查询传给 LLM 的 user prompt")
    void rewrite_passesOriginalQueryToLlm() {
        queryRewriter.setQueryRewriteEnabled(true);

        String llmResponse = "{\"rewrittenQuery\": \"some query\"}";
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(llmResponse);

        queryRewriter.rewrite("原始查询内容");

        verify(requestSpec).user("原始查询内容");
    }
}
```

- [ ] **Step 2: 运行确认编译失败（QueryRewriter 不存在）**

```bash
./mvnw test -Dtest=QueryRewriterTest -q 2>&1 | grep -E "ERROR|cannot find" | head -5
```

Expected: 编译错误，`QueryRewriter` 类不存在。

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/dawn/ai/service/QueryRewriterTest.java
git commit -m "test: add failing QueryRewriterTest (TDD Red)"
```

---

## Task 3：实现 QueryRewriter（TDD Green）

**Files:**
- Create: `src/main/java/com/dawn/ai/service/QueryRewriter.java`

- [ ] **Step 1: 创建实现文件**

```java
package com.dawn.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Rewrites user queries into vector-search-friendly keyword phrases.
 *
 * When queryRewriteEnabled=false, returns the original query unchanged.
 * This avoids an extra LLM call for simple or already precise queries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriter {

    private final ChatClient chatClient;

    @Setter
    @Value("${app.ai.rag.query-rewrite-enabled:true}")
    private boolean queryRewriteEnabled;

    record RewriteResult(String rewrittenQuery) {}

    /**
     * Rewrites the given query for better vector search recall.
     *
     * @param originalQuery the raw user query
     * @return rewritten query, or originalQuery if rewriting is disabled
     */
    public String rewrite(String originalQuery) {
        if (!queryRewriteEnabled) {
            log.debug("[QueryRewriter] Disabled, using original query: {}", originalQuery);
            return originalQuery;
        }

        BeanOutputConverter<RewriteResult> converter =
                new BeanOutputConverter<>(RewriteResult.class);

        String response = chatClient.prompt()
                .system("将用户问题改写为适合向量检索的关键词短语，保留核心语义，去除口语助词。"
                        + converter.getFormat())
                .user(originalQuery)
                .options(OpenAiChatOptions.builder().temperature(0.1).build())
                .call()
                .content();

        String rewritten = converter.convert(response).rewrittenQuery();
        log.debug("[QueryRewriter] '{}' → '{}'", originalQuery, rewritten);
        return rewritten;
    }
}
```

- [ ] **Step 2: 运行 QueryRewriterTest，确认全部通过**

```bash
./mvnw test -Dtest=QueryRewriterTest -q 2>&1 | tail -10
```

Expected:
```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 3: 运行全套测试，确认无回归**

```bash
./mvnw test -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dawn/ai/service/QueryRewriter.java \
        src/test/java/com/dawn/ai/service/QueryRewriterTest.java
git commit -m "feat: add QueryRewriter with BeanOutputConverter and enable/disable toggle"
```

---

## Task 4：写 KnowledgeSearchToolTest（TDD Red）

**Files:**
- Create: `src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolTest.java`

- [ ] **Step 1: 创建测试文件**

```java
package com.dawn.ai.agent.tools;

import com.dawn.ai.service.QueryRewriter;
import com.dawn.ai.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchToolTest {

    @Mock private QueryRewriter queryRewriter;
    @Mock private RagService ragService;

    private KnowledgeSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new KnowledgeSearchTool(queryRewriter, ragService);
        tool.setDefaultTopK(5);
    }

    @Test
    @DisplayName("apply: 调用 QueryRewriter 改写查询后再检索")
    void apply_rewritesQueryBeforeRetrieval() {
        when(queryRewriter.rewrite("月费多少")).thenReturn("Dawn AI 定价 月费");
        when(ragService.retrieve("Dawn AI 定价 月费", 5)).thenReturn(List.of());

        tool.apply(new KnowledgeSearchTool.Request("月费多少"));

        verify(queryRewriter).rewrite("月费多少");
        verify(ragService).retrieve("Dawn AI 定价 月费", 5);
    }

    @Test
    @DisplayName("apply: 有检索结果时返回格式化 context 和正确 docsFound")
    void apply_withResults_returnsFormattedContextAndCount() {
        when(queryRewriter.rewrite("pricing")).thenReturn("pricing");
        when(ragService.retrieve("pricing", 5)).thenReturn(List.of(
                new Document("月费 ¥99"),
                new Document("年费 ¥888")
        ));

        KnowledgeSearchTool.Response response =
                tool.apply(new KnowledgeSearchTool.Request("pricing"));

        assertThat(response.docsFound()).isEqualTo(2);
        assertThat(response.context()).contains("[1]").contains("月费 ¥99");
        assertThat(response.context()).contains("[2]").contains("年费 ¥888");
    }

    @Test
    @DisplayName("apply: 无检索结果时返回提示文字和 docsFound=0")
    void apply_withNoResults_returnsEmptyMessage() {
        when(queryRewriter.rewrite("unknown")).thenReturn("unknown");
        when(ragService.retrieve("unknown", 5)).thenReturn(List.of());

        KnowledgeSearchTool.Response response =
                tool.apply(new KnowledgeSearchTool.Request("unknown"));

        assertThat(response.docsFound()).isEqualTo(0);
        assertThat(response.context()).isEqualTo("未找到相关知识库内容。");
    }
}
```

- [ ] **Step 2: 运行确认编译失败**

```bash
./mvnw test -Dtest=KnowledgeSearchToolTest -q 2>&1 | grep -E "ERROR|cannot find" | head -5
```

Expected: 编译错误，`KnowledgeSearchTool` 不存在。

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolTest.java
git commit -m "test: add failing KnowledgeSearchToolTest (TDD Red)"
```

---

## Task 5：实现 KnowledgeSearchTool（TDD Green）

**Files:**
- Create: `src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java`

- [ ] **Step 1: 创建实现文件**

```java
package com.dawn.ai.agent.tools;

import com.dawn.ai.service.QueryRewriter;
import com.dawn.ai.service.RagService;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 * Invocation flow:
 *   LLM decides to search → QueryRewriter rewrites query → RagService retrieves chunks
 */
@Slf4j
@Component
@Description("搜索内部知识库，获取与问题相关的背景信息。需要查询产品信息、技术文档或领域知识时调用。")
@RequiredArgsConstructor
public class KnowledgeSearchTool implements Function<KnowledgeSearchTool.Request, KnowledgeSearchTool.Response> {

    private final QueryRewriter queryRewriter;
    private final RagService ragService;

    @Setter
    @Value("${app.ai.rag.default-top-k:5}")
    private int defaultTopK;

    public record Request(@JsonProperty(required = true) String query) {}
    public record Response(String context, int docsFound) {}

    @Override
    public Response apply(Request req) {
        String rewrittenQuery = queryRewriter.rewrite(req.query());
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

- [ ] **Step 2: 运行 KnowledgeSearchToolTest，确认全部通过**

```bash
./mvnw test -Dtest=KnowledgeSearchToolTest -q 2>&1 | tail -10
```

Expected:
```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 3: 运行全套测试**

```bash
./mvnw test -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java \
        src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolTest.java
git commit -m "feat: add KnowledgeSearchTool — Agentic RAG Level 1-2"
```

---

## Task 6：删除 ChatService RAG 预处理（消除 Bug B1）

**Files:**
- Modify: `src/main/java/com/dawn/ai/service/ChatService.java`
- Modify: `src/main/java/com/dawn/ai/dto/ChatRequest.java`

- [ ] **Step 1: 删除 ChatService 中的 RAG 预处理逻辑**

在 `ChatService.java` 的 `chat()` 方法中，删除以下代码块（含 `ragService` 字段和相关 import）：

删除字段：
```java
private final RagService ragService;
```

删除 `chat()` 方法中的：
```java
if (request.isRagEnabled()) {
    String context = ragService.buildContext(userMessage);
    if (!context.isBlank()) {
        userMessage = context + "\n\nUser question: " + userMessage;
    }
}
```

修改后 `chat()` 方法头部：
```java
public ChatResponse chat(ChatRequest request) {
    long start = System.currentTimeMillis();

    aiAvailabilityChecker.ensureConfigured();

    String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
            ? request.getSessionId()
            : UUID.randomUUID().toString();

    String userMessage = request.getMessage();

    AgentResult result = agentOrchestrator.chat(sessionId, userMessage);
    // ... 其余不变
```

同时删除 `RagService` 相关 import：
```java
import com.dawn.ai.service.RagService;
```

- [ ] **Step 2: 删除 ChatRequest.ragEnabled 字段**

在 `ChatRequest.java` 中删除：
```java
/** Whether to enable RAG retrieval */
private boolean ragEnabled = false;
```

修改后 `ChatRequest.java` 完整内容：
```java
package com.dawn.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {

    @NotBlank(message = "Message cannot be blank")
    private String message;

    /** Conversation session ID for memory context */
    private String sessionId;
}
```

- [ ] **Step 3: 运行全套测试**

```bash
./mvnw test -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS（ChatService 的依赖减少，不会引入新测试失败）。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dawn/ai/service/ChatService.java \
        src/main/java/com/dawn/ai/dto/ChatRequest.java
git commit -m "feat: remove RAG preprocessing from ChatService, fix Bug B1 history pollution"
```

---

## 完成标准

- [ ] `QueryRewriterTest` 3 个用例全部 GREEN
- [ ] `KnowledgeSearchToolTest` 3 个用例全部 GREEN
- [ ] `./mvnw test` 全套 BUILD SUCCESS
- [ ] `ChatRequest` 不再有 `ragEnabled` 字段
- [ ] `ChatService` 不再有 RAG 预处理逻辑
- [ ] `KnowledgeSearchTool` 在 `tools` 包（ToolRegistry 自动感知，TaskPlanner 自动感知）
