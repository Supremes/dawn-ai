# Second Brain: Topic Metadata 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 dawn-ai 新增 `topicId` 字段，让用户能把文档归组到研究主题下，Agent 在检索和对话时自动限定主题范围。

**Architecture:** `topicId` 作纯 metadata tag 贯穿 RagService 入库 → pgvector filter → KnowledgeSearchTool → AgentOrchestrator system prompt 注入，全部增量扩展，不新建实体或额外存储。

**Tech Stack:** Java 17, Spring Boot 3.2.5, Spring AI 1.1.4, JUnit 5, Mockito, MockMvc

---

## 文件改动一览

| 文件 | 操作 |
|---|---|
| `src/main/java/com/dawn/ai/dto/RagRequest.java` | 新增 `topicId` 字段 |
| `src/main/java/com/dawn/ai/dto/ChatRequest.java` | 新增 `topicId` 字段 |
| `src/main/java/com/dawn/ai/rag/RagService.java` | `ingest()` 加 `topicId` 参数，写入 metadata |
| `src/main/java/com/dawn/ai/controller/RagController.java` | JSON/multipart ingest + search 透传 `topicId` |
| `src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java` | `Request` 加 `topicId`，写入 filter |
| `src/main/java/com/dawn/ai/agent/orchestration/AgentOrchestrator.java` | `chat()`/`streamChat()`/`buildSystemPrompt()` 加 `topicId` |
| `src/main/java/com/dawn/ai/service/ChatService.java` | 从 `ChatRequest` 取 `topicId` 传入 orchestrator |
| `src/test/java/com/dawn/ai/rag/RagServiceTest.java` | 新增 topicId 相关测试 |
| `src/test/java/com/dawn/ai/controller/RagControllerValidationTest.java` | 新增 topicId 透传测试 |
| `src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolTopicTest.java` | 新建，测试 topicId filter |

---

## Task 1: 扩展 `RagRequest` DTO + `RagService.ingest()` 写入 topicId metadata

**Files:**
- Modify: `src/main/java/com/dawn/ai/dto/RagRequest.java`
- Modify: `src/main/java/com/dawn/ai/rag/RagService.java`
- Modify: `src/test/java/com/dawn/ai/rag/RagServiceTest.java`

- [ ] **Step 1: 在 `RagServiceTest` 中写两个失败测试**

在 `src/test/java/com/dawn/ai/rag/RagServiceTest.java` 的已有 `@BeforeEach` 之后添加：

```java
@Test
@DisplayName("ingest with topicId should include topicId in chunk metadata")
void ingest_withTopicId_shouldIncludeTopicIdInMetadata() {
    ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);

    ragService.ingest("distributed tx content", "saga.pdf", "general", "distributed-tx");

    verify(vectorStore).add(captor.capture());
    assertThat(captor.getValue())
        .isNotEmpty()
        .allSatisfy(doc ->
            assertThat(doc.getMetadata()).containsEntry("topicId", "distributed-tx"));
}

@Test
@DisplayName("ingest with null topicId should not include topicId key in metadata")
void ingest_withNullTopicId_shouldNotAddTopicIdKey() {
    ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);

    ragService.ingest("content", "source.pdf", "general", null);

    verify(vectorStore).add(captor.capture());
    assertThat(captor.getValue())
        .allSatisfy(doc ->
            assertThat(doc.getMetadata()).doesNotContainKey("topicId"));
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
./mvnw test -pl . -Dtest=RagServiceTest#ingest_withTopicId_shouldIncludeTopicIdInMetadata+ingest_withNullTopicId_shouldNotAddTopicIdKey -q 2>&1 | tail -20
```

期望：编译失败，原因是 `ingest()` 还不接受 4 个参数。

- [ ] **Step 3: 新增 `topicId` 字段到 `RagRequest`**

```java
// src/main/java/com/dawn/ai/dto/RagRequest.java
@Data
public class RagRequest {

    @NotBlank
    private String content;

    private String source;

    private String category;

    private String topicId;  // optional, used to group documents under a research topic
}
```

- [ ] **Step 4: 扩展 `RagService.ingest()` 写入 topicId**

将 `src/main/java/com/dawn/ai/rag/RagService.java` 中的 `ingest` 方法替换为：

```java
public String ingest(String content, String source, String category) {
    return ingest(content, source, category, null);
}

public String ingest(String content, String source, String category, String topicId) {
    aiAvailabilityChecker.ensureConfigured();

    String docId = UUID.randomUUID().toString();
    Map<String, Object> metadata = new java.util.HashMap<>();
    metadata.put("source", source != null ? source : "manual");
    metadata.put("category", category != null ? category : "general");
    metadata.put("docId", docId);
    if (topicId != null && !topicId.isBlank()) {
        metadata.put("topicId", topicId);
    }
    Document parentDoc = new Document(docId, content, metadata);

    List<Document> chunks = splitter.apply(List.of(parentDoc));

    vectorStore.add(chunks);
    ingestionCounter.increment(chunks.size());

    log.info("[RagService] Ingested {} chunk(s), source={}, topicId={}", chunks.size(), source, topicId);
    return docId;
}
```

- [ ] **Step 5: 运行测试，确认通过**

```bash
./mvnw test -pl . -Dtest=RagServiceTest -q 2>&1 | tail -10
```

期望：`BUILD SUCCESS`，所有 RagServiceTest 用例通过。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/dawn/ai/dto/RagRequest.java \
        src/main/java/com/dawn/ai/rag/RagService.java \
        src/test/java/com/dawn/ai/rag/RagServiceTest.java
git commit -m "feat: extend RagService.ingest() to write topicId into chunk metadata"
```

---

## Task 2: 更新 `RagController` 透传 `topicId`

**Files:**
- Modify: `src/main/java/com/dawn/ai/controller/RagController.java`
- Modify: `src/test/java/com/dawn/ai/controller/RagControllerValidationTest.java`

- [ ] **Step 1: 在 `RagControllerValidationTest` 中写失败测试**

在现有测试类末尾添加：

```java
@Test
void ingestJson_withTopicId_shouldPassTopicIdToService() throws Exception {
    when(ragService.ingest(any(), any(), any(), any())).thenReturn("doc-1");

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/api/v1/rag/ingest")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"content\":\"hello\",\"source\":\"s\",\"topicId\":\"distributed-tx\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.docId").value("doc-1"));

    verify(ragService).ingest("hello", "s", null, "distributed-tx");
}

@Test
void ingestMultipart_withTopicId_shouldPassTopicIdToService() throws Exception {
    when(documentTextExtractor.extract(any(), any())).thenReturn("text content");
    when(ragService.ingest(any(), any(), any(), any())).thenReturn("doc-2");

    MockMultipartFile file = new MockMultipartFile("file", "note.txt",
            "text/plain", "hello".getBytes());

    mockMvc.perform(multipart("/api/v1/rag/ingest")
            .file(file)
            .param("topicId", "distributed-tx"))
        .andExpect(status().isOk());

    verify(ragService).ingest(eq("text content"), any(), any(), eq("distributed-tx"));
}
```

- [ ] **Step 2: 运行，确认失败**

```bash
./mvnw test -pl . -Dtest=RagControllerValidationTest#ingestJson_withTopicId_shouldPassTopicIdToService+ingestMultipart_withTopicId_shouldPassTopicIdToService -q 2>&1 | tail -15
```

期望：编译通过，但测试失败（`ragService.ingest` 调用签名不匹配）。

- [ ] **Step 3: 更新 `RagController` JSON ingest 方法**

将 `ingest(@Valid @RequestBody RagRequest request)` 中的 service 调用改为：

```java
String docId = ragService.ingest(request.getContent(), request.getSource(), request.getCategory(), request.getTopicId());
```

- [ ] **Step 4: 更新 `RagController` multipart ingest 方法**

在 `ingestFile` 方法签名加参数，并更新 service 调用：

```java
@PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ResponseEntity<Map<String, String>> ingestFile(
        @RequestPart("file") MultipartFile file,
        @RequestParam(required = false) DocumentType documentType,
        @RequestParam(required = false) String source,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String topicId) {   // 新增
    if (file.isEmpty()) {
        throw new IllegalArgumentException("Uploaded file is empty");
    }

    DocumentType resolvedType = documentType != null ? documentType : inferDocumentType(file);
    String content = documentTextExtractor.extract(file, resolvedType);
    String effectiveSource = (source != null && !source.isBlank()) ? source : file.getOriginalFilename();
    String docId = ragService.ingest(content, effectiveSource, category, topicId);  // 新增 topicId
    return ResponseEntity.ok(Map.of(
            "docId", docId,
            "status", "ingested",
            "documentType", resolvedType.name()
    ));
}
```

- [ ] **Step 5: 更新 search 端点支持 topicId filter**

在 `search()` 方法签名加 `topicId` 参数，并在 `buildMetadataFilters` 中传入：

```java
@GetMapping("/search")
public ResponseEntity<List<Document>> search(
        @RequestParam String query,
        @RequestParam(defaultValue = "5") @Min(1) @Max(value = 20, message = "must be less than or equal to 20") int topK,
        @RequestParam(required = false) List<String> source,
        @RequestParam(required = false) List<String> category,
        @RequestParam(required = false, name = "docId") List<String> docIds,
        @RequestParam(required = false) List<String> topicId,   // 新增
        @RequestParam(defaultValue = "AUTO") RetrievalStrategy strategy) {
    RetrievalRequest request = RetrievalRequest.builder()
            .query(query)
            .topK(topK)
            .strategy(strategy)
            .metadataFilters(buildMetadataFilters(source, category, docIds, topicId))  // 新增 topicId
            .build();
    List<Document> results = ragService.retrieve(request);
    return ResponseEntity.ok(results);
}
```

更新 `buildMetadataFilters` 方法签名和实现：

```java
private Map<String, List<String>> buildMetadataFilters(
        List<String> source,
        List<String> category,
        List<String> docIds,
        List<String> topicId) {   // 新增参数
    Map<String, List<String>> filters = new LinkedHashMap<>();
    addFilter(filters, "source", source);
    addFilter(filters, "category", category);
    addFilter(filters, "docId", docIds);
    addFilter(filters, "topicId", topicId);   // 新增
    return filters;
}
```

- [ ] **Step 6: 运行全部 Controller 测试**

```bash
./mvnw test -pl . -Dtest=RagControllerValidationTest -q 2>&1 | tail -10
```

期望：`BUILD SUCCESS`。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/dawn/ai/controller/RagController.java \
        src/test/java/com/dawn/ai/controller/RagControllerValidationTest.java
git commit -m "feat: RagController passes topicId through ingest and search endpoints"
```

---

## Task 3: 更新 `KnowledgeSearchTool` 支持 topicId filter

**Files:**
- Modify: `src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java`
- Create: `src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolTopicTest.java`

- [ ] **Step 1: 新建测试文件**

创建 `src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolTopicTest.java`：

```java
package com.dawn.ai.agent.tools;

import com.dawn.ai.agent.trace.StepCollector;
import com.dawn.ai.rag.RagService;
import com.dawn.ai.rag.retrieval.RetrievalRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchToolTopicTest {

    @Mock private com.dawn.ai.rag.query.QueryRewriter queryRewriter;
    @Mock private RagService ragService;

    private KnowledgeSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new KnowledgeSearchTool(queryRewriter, ragService, new SimpleMeterRegistry());
        tool.setDefaultTopK(5);
        tool.initMetrics();
        StepCollector.init(10);
        when(queryRewriter.rewrite(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of());
    }

    @Test
    void apply_withTopicId_shouldIncludeTopicIdInMetadataFilter() {
        ArgumentCaptor<RetrievalRequest> captor = ArgumentCaptor.forClass(RetrievalRequest.class);

        tool.apply(new KnowledgeSearchTool.Request("what is saga", null, null, null, "distributed-tx"));

        verify(ragService).retrieve(captor.capture());
        assertThat(captor.getValue().getMetadataFilters())
            .containsKey("topicId")
            .extractingByKey("topicId")
            .asList()
            .containsExactly("distributed-tx");
    }

    @Test
    void apply_withNullTopicId_shouldNotAddTopicIdFilter() {
        ArgumentCaptor<RetrievalRequest> captor = ArgumentCaptor.forClass(RetrievalRequest.class);

        tool.apply(new KnowledgeSearchTool.Request("what is saga", null, null, null, null));

        verify(ragService).retrieve(captor.capture());
        assertThat(captor.getValue().getMetadataFilters()).doesNotContainKey("topicId");
    }
}
```

- [ ] **Step 2: 运行，确认编译失败**

```bash
./mvnw test -pl . -Dtest=KnowledgeSearchToolTopicTest -q 2>&1 | tail -15
```

期望：编译失败，`Request` 还没有 `topicId` 参数。

- [ ] **Step 3: 更新 `KnowledgeSearchTool.Request` record**

将 `Request` record 替换为：

```java
public record Request(
        @JsonProperty(required = true) String query,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Only set when the user explicitly names a source (e.g. 'search in devops-notes'). Do NOT guess or invent a value.")
        String source,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Only set when the user explicitly names a category. Do NOT guess or invent a value.")
        String category,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Only set when the user explicitly provides a document ID. Do NOT guess or invent a value.")
        String docId,
        @JsonProperty(required = false)
        @JsonPropertyDescription("Research topic ID from the system prompt context. Always use the topicId value provided in the system prompt when one is present.")
        String topicId
) {
    public Request(String query) {
        this(query, null, null, null, null);
    }
}
```

- [ ] **Step 4: 更新 `buildMetadataFilters` 加入 topicId**

```java
private Map<String, List<String>> buildMetadataFilters(Request req) {
    Map<String, List<String>> filters = new LinkedHashMap<>();
    addFilter(filters, "source", req.source());
    addFilter(filters, "category", req.category());
    addFilter(filters, "docId", req.docId());
    addFilter(filters, "topicId", req.topicId());
    return filters;
}
```

- [ ] **Step 5: 运行测试，确认通过**

```bash
./mvnw test -pl . -Dtest=KnowledgeSearchToolTopicTest -q 2>&1 | tail -10
```

期望：`BUILD SUCCESS`。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/dawn/ai/agent/tools/KnowledgeSearchTool.java \
        src/test/java/com/dawn/ai/agent/tools/KnowledgeSearchToolTopicTest.java
git commit -m "feat: KnowledgeSearchTool supports topicId metadata filter"
```

---

## Task 4: 扩展 `ChatRequest` + `AgentOrchestrator` 注入 topicId 到 system prompt

**Files:**
- Modify: `src/main/java/com/dawn/ai/dto/ChatRequest.java`
- Modify: `src/main/java/com/dawn/ai/agent/orchestration/AgentOrchestrator.java`

- [ ] **Step 1: 新增 `topicId` 到 `ChatRequest`**

```java
// src/main/java/com/dawn/ai/dto/ChatRequest.java
@Data
public class ChatRequest {

    @NotBlank(message = "Message cannot be blank")
    private String message;

    private String sessionId;

    private String topicId;  // optional research topic context
}
```

- [ ] **Step 2: 更新 `AgentOrchestrator.chat()` 签名**

将：
```java
public AgentResult chat(String sessionId, String userMessage) {
    return Timer.builder("ai.agent.chat.duration")
            .tag("session", "anonymous")
            .register(meterRegistry)
            .record(() -> doChat(sessionId, userMessage));
}
```
替换为：
```java
public AgentResult chat(String sessionId, String userMessage, String topicId) {
    return Timer.builder("ai.agent.chat.duration")
            .tag("session", "anonymous")
            .register(meterRegistry)
            .record(() -> doChat(sessionId, userMessage, topicId));
}
```

- [ ] **Step 3: 更新 `AgentOrchestrator.doChat()` 签名和 systemPrompt 调用**

将：
```java
private AgentResult doChat(String sessionId, String userMessage) {
    StepCollector.init(maxSteps);
    try {
        TaskPlanner.PlannerResult plannerResult = resolvePlan(userMessage);
        List<PlanStep> plan = plannerResult.steps();

        String systemPrompt = buildSystemPrompt(plan, sessionId);
```
替换为：
```java
private AgentResult doChat(String sessionId, String userMessage, String topicId) {
    StepCollector.init(maxSteps);
    try {
        TaskPlanner.PlannerResult plannerResult = resolvePlan(userMessage);
        List<PlanStep> plan = plannerResult.steps();

        String systemPrompt = buildSystemPrompt(plan, sessionId, topicId);
```

- [ ] **Step 4: 更新 `AgentOrchestrator.streamChat()` 签名和 systemPrompt 调用**

将：
```java
public void streamChat(String sessionId, String userMessage, Consumer<ChatStreamEvent> sink,
                       BooleanSupplier isCancelled) {
```
替换为：
```java
public void streamChat(String sessionId, String userMessage, String topicId,
                       Consumer<ChatStreamEvent> sink, BooleanSupplier isCancelled) {
```

在 `streamChat` 内部找到 `buildSystemPrompt(plan, sessionId)` 调用，改为：
```java
String systemPrompt = buildSystemPrompt(plan, sessionId, topicId);
```

- [ ] **Step 5: 更新 `buildSystemPrompt()` 注入 topicId**

将：
```java
private String buildSystemPrompt(List<PlanStep> plan, String sessionId) {
    String profileSection = userProfileService.formatForSystemPrompt(sessionId);
    return baseSystemPrompt
            + profileSection
            + formatPlan(plan)
            + formatPlanEnforcement(plan)
            + String.format("%n请在回复中简短说明每次工具调用的原因。最多调用工具 %d 次。", maxSteps);
}
```
替换为：
```java
private String buildSystemPrompt(List<PlanStep> plan, String sessionId, String topicId) {
    String profileSection = userProfileService.formatForSystemPrompt(sessionId);
    String topicSection = (topicId != null && !topicId.isBlank())
            ? String.format("%n%n【研究主题】你当前在帮助用户研究主题：%s。" +
              "调用 KnowledgeSearchTool 时，topicId 参数必须使用 \"%s\"。", topicId, topicId)
            : "";
    return baseSystemPrompt
            + profileSection
            + topicSection
            + formatPlan(plan)
            + formatPlanEnforcement(plan)
            + String.format("%n请在回复中简短说明每次工具调用的原因。最多调用工具 %d 次。", maxSteps);
}
```

- [ ] **Step 6: 运行完整测试套件确认无回归**

```bash
./mvnw test -q 2>&1 | tail -15
```

期望：`BUILD SUCCESS`（ChatService 尚未更新，会有编译错误 — 先看报错位置）。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/dawn/ai/dto/ChatRequest.java \
        src/main/java/com/dawn/ai/agent/orchestration/AgentOrchestrator.java
git commit -m "feat: AgentOrchestrator injects topicId into system prompt"
```

---

## Task 5: 更新 `ChatService` 透传 topicId，完成端到端串联

**Files:**
- Modify: `src/main/java/com/dawn/ai/service/ChatService.java`

- [ ] **Step 1: 更新 `ChatService.chat()` 传入 topicId**

找到 `chat()` 方法中的 orchestrator 调用，从：
```java
AgentResult result = agentOrchestrator.chat(sessionId, userMessage);
```
改为：
```java
AgentResult result = agentOrchestrator.chat(sessionId, userMessage, request.getTopicId());
```

- [ ] **Step 2: 更新 `ChatService.streamChat()` 传入 topicId**

找到 `streamChat()` 方法中的 orchestrator 调用，从：
```java
agentOrchestrator.streamChat(sessionId, request.getMessage(),
```
改为：
```java
agentOrchestrator.streamChat(sessionId, request.getMessage(), request.getTopicId(),
```

- [ ] **Step 3: 运行完整测试套件**

```bash
./mvnw test -q 2>&1 | tail -15
```

期望：`BUILD SUCCESS`，全部测试通过。

- [ ] **Step 4: 手动验证端到端（可选，需启动服务）**

```bash
# 1. 启动服务
./mvnw spring-boot:run &

# 2. 上传文档到 distributed-tx 主题
curl -s -X POST http://localhost:8080/api/v1/rag/ingest \
  -H "Content-Type: application/json" \
  -d '{"content":"Saga 模式是一种分布式事务解决方案，通过补偿事务保证最终一致性。","source":"saga.md","topicId":"distributed-tx"}' | jq .

# 3. 用 topicId 限定主题对话
curl -s -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"介绍一下 Saga 模式","sessionId":"user1:distributed-tx","topicId":"distributed-tx"}' | jq .answer
```

期望：回答内容来自刚上传的文档，而非 LLM 训练知识（可通过删除文档后对比验证）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dawn/ai/service/ChatService.java
git commit -m "feat: ChatService passes topicId end-to-end through orchestrator"
```

---

## 自审 Checklist

- [x] **Spec 覆盖**：RagService ingest ✅ | KnowledgeSearchTool filter ✅ | ChatController topicId ✅ | system prompt 注入 ✅
- [x] **无 Placeholder**：所有步骤都有完整代码
- [x] **类型一致性**：`topicId` 全程 `String`，`Request` record 5 参数构造函数在测试和实现中一致
- [x] **向后兼容**：无 `topicId` 的请求行为与改动前完全一致
