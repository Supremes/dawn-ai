# RAG Phase 1 — Retrieval Quality Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 RAG 检索层加上 Chunking（TokenTextSplitter）和相似度阈值过滤，让检索结果"宁缺毋滥"，并补充可观测指标。

**Architecture:** 在 `RagService` 中引入 Spring AI `TokenTextSplitter`，将入库文档拆成 500 token 的 chunk；`retrieve()` 改为先取 topK×2 候选再用 `similarityThreshold=0.7` 过滤，最后限制返回 topK 条。新增 `ai.rag.retrieval.filtered_count` Histogram 指标。

**Tech Stack:** Spring AI 1.1.4、pgvector、Micrometer、JUnit 5、Mockito、AssertJ

---

## File Map

| 文件 | 操作 | 职责 |
|---|---|---|
| `src/main/resources/application.yml` | Modify | 新增 `app.ai.rag` 配置节 |
| `src/main/java/com/dawn/ai/service/RagService.java` | Modify | Chunking + 阈值过滤 + 新指标 |
| `src/test/java/com/dawn/ai/service/RagServiceTest.java` | Create | 单元测试：Chunking、阈值过滤、指标 |

---

## Task 1：新增 RAG 配置项

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 在 `application.yml` 的 `app.ai` 节下追加 RAG 配置**

在文件 `app.ai.react` 块后面追加（保持缩进对齐）：

```yaml
app:
  ai:
    react:
      max-steps: 10
      show-steps: true
      plan-enabled: true
    rag:
      similarity-threshold: 0.7   # 向量相似度过滤阈值，低于此值的文档被丢弃
      default-top-k: 5            # 最终返回的文档数
    system-prompt: |
      ...（保持原样不动）
```

完整修改后 `app.ai` 节如下：

```yaml
app:
  ai:
    react:
      max-steps: 10
      show-steps: true
      plan-enabled: true
    rag:
      similarity-threshold: 0.7
      default-top-k: 5
    system-prompt: |
      You are Dawn AI, a helpful and knowledgeable assistant powered by advanced AI.
      You can help with calculations, weather queries, and answer questions based on your knowledge base.
      Always be concise, accurate, and helpful.
      Before calling a tool, briefly explain your reasoning in one sentence.
```

- [ ] **Step 2: 验证 YAML 格式正确**

```bash
./mvnw validate -q
```

Expected: 无报错输出。

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "config: add app.ai.rag similarity-threshold and default-top-k"
```

---

## Task 2：写失败测试（TDD Red）

**Files:**
- Create: `src/test/java/com/dawn/ai/service/RagServiceTest.java`

- [ ] **Step 1: 创建测试文件**

```java
package com.dawn.ai.service;

import com.dawn.ai.config.AiAvailabilityChecker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RagServiceTest {

    @Mock private VectorStore vectorStore;
    @Mock private AiAvailabilityChecker aiAvailabilityChecker;

    private SimpleMeterRegistry meterRegistry;
    private RagService ragService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
        ragService = new RagService(vectorStore, meterRegistry, aiAvailabilityChecker);
        // 注入配置值（与 application.yml 一致）
        ragService.setSimilarityThreshold(0.7);
        ragService.setDefaultTopK(5);
        // @PostConstruct 在直接 new 时不自动执行，手动初始化指标
        ragService.initMetrics();
    }

    // ── ingest 测试 ────────────────────────────────────────────

    @Test
    @DisplayName("ingest: 短文本(<=500 tokens)应存为单个 chunk")
    void ingest_shortContent_storesSingleChunk() {
        String shortContent = "Dawn AI is an intelligent assistant.";
        ragService.ingest(shortContent, "test", "general");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getText()).contains("Dawn AI");
    }

    @Test
    @DisplayName("ingest: 长文本(>500 tokens)应拆分为多个 chunk")
    void ingest_longContent_storesMultipleChunks() {
        // 生成约 1000 tokens 的文本（英文约 4 chars/token）
        String longContent = "word ".repeat(600);
        ragService.ingest(longContent, "doc", "manual");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertThat(captor.getValue().size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("ingest: 每个 chunk 应继承父文档的 source 和 category metadata")
    void ingest_chunksInheritMetadata() {
        String content = "Some content about Dawn AI pricing and features.";
        ragService.ingest(content, "pricing-doc", "billing");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Document chunk = captor.getValue().get(0);
        assertThat(chunk.getMetadata()).containsEntry("source", "pricing-doc");
        assertThat(chunk.getMetadata()).containsEntry("category", "billing");
    }

    // ── retrieve 测试 ──────────────────────────────────────────

    @Test
    @DisplayName("retrieve: 应使用 similarityThreshold 和 topK*2 候选数构建 SearchRequest")
    void retrieve_buildsSearchRequestWithThresholdAndDoubledTopK() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());

        ragService.retrieve("test query", 5);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        SearchRequest req = captor.getValue();
        assertThat(req.getTopK()).isEqualTo(10);           // topK * 2
        assertThat(req.getSimilarityThreshold()).isEqualTo(0.7);
    }

    @Test
    @DisplayName("retrieve: 结果超过 topK 时应截断为 topK 条")
    void retrieve_truncatesToTopK() {
        List<Document> eightDocs = List.of(
            new Document("1"), new Document("2"), new Document("3"),
            new Document("4"), new Document("5"), new Document("6"),
            new Document("7"), new Document("8")
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(eightDocs);

        List<Document> result = ragService.retrieve("query", 5);

        assertThat(result).hasSize(5);
    }

    @Test
    @DisplayName("retrieve: 结果为空时应增加 miss 计数器")
    void retrieve_emptyResult_incrementsMissCounter() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        ragService.retrieve("query", 5);

        double missCount = meterRegistry.counter("ai.rag.retrieval.total", "result", "miss").count();
        assertThat(missCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("retrieve: 有结果时应增加 hit 计数器")
    void retrieve_nonEmptyResult_incrementsHitCounter() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("content")));

        ragService.retrieve("query", 5);

        double hitCount = meterRegistry.counter("ai.rag.retrieval.total", "result", "hit").count();
        assertThat(hitCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("retrieve: 应记录 filtered_count 指标（候选数 - 返回数）")
    void retrieve_recordsFilteredCountMetric() {
        // 请求 topK=5 → 候选数=10，向量库返回 3 条（阈值过滤后）
        List<Document> threeDocs = List.of(
            new Document("a"), new Document("b"), new Document("c")
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(threeDocs);

        ragService.retrieve("query", 5);

        // filtered_count = 候选数(10) - 实际返回(3) = 7
        double filteredSum = meterRegistry.summary("ai.rag.retrieval.filtered_count").totalAmount();
        assertThat(filteredSum).isEqualTo(7.0);
    }
}
```

- [ ] **Step 2: 运行测试，确认全部 FAIL（类不存在 setter 方法）**

```bash
./mvnw test -Dtest=RagServiceTest -q 2>&1 | tail -20
```

Expected: 编译失败或测试失败，包含 `setSimilarityThreshold` / `setDefaultTopK` 不存在的报错。

---

## Task 3：实现 RagService 改造（TDD Green）

**Files:**
- Modify: `src/main/java/com/dawn/ai/service/RagService.java`

- [ ] **Step 1: 替换 `RagService.java` 全部内容**

```java
package com.dawn.ai.service;

import com.dawn.ai.config.AiAvailabilityChecker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RAG (Retrieval Augmented Generation) Service.
 *
 * Pipeline: Document → Chunk(500 tokens, overlap=50) → Embed → Store
 *           → Query → SimilarityThreshold filter → Augment Prompt → Generate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final VectorStore vectorStore;
    private final MeterRegistry meterRegistry;
    private final AiAvailabilityChecker aiAvailabilityChecker;

    @Setter
    @Value("${app.ai.rag.similarity-threshold:0.7}")
    private double similarityThreshold;

    @Setter
    @Value("${app.ai.rag.default-top-k:5}")
    private int defaultTopK;

    private Counter ingestionCounter;
    private Counter retrievalHitCounter;
    private Counter retrievalMissCounter;
    private DistributionSummary filteredCountSummary;

    @PostConstruct
    void initMetrics() {
        ingestionCounter = Counter.builder("ai.rag.ingestion.total")
                .description("Total documents ingested into vector store")
                .register(meterRegistry);
        retrievalHitCounter = Counter.builder("ai.rag.retrieval.total")
                .description("Total RAG retrieval queries")
                .tag("result", "hit")
                .register(meterRegistry);
        retrievalMissCounter = Counter.builder("ai.rag.retrieval.total")
                .description("Total RAG retrieval queries")
                .tag("result", "miss")
                .register(meterRegistry);
        filteredCountSummary = DistributionSummary.builder("ai.rag.retrieval.filtered_count")
                .description("Documents filtered out per retrieval (candidates - returned)")
                .register(meterRegistry);
    }

    /**
     * Ingest a document into the vector store.
     * Long documents are split into chunks of ~500 tokens with 50-token overlap.
     * Each chunk inherits the parent document's source and category metadata.
     */
    public String ingest(String content, String source, String category) {
        aiAvailabilityChecker.ensureConfigured();

        Map<String, Object> metadata = Map.of(
                "source", source != null ? source : "manual",
                "category", category != null ? category : "general"
        );
        Document parentDoc = new Document(UUID.randomUUID().toString(), content, metadata);

        TokenTextSplitter splitter = new TokenTextSplitter(500, 350, 5, 10000, true);
        List<Document> chunks = splitter.apply(List.of(parentDoc));

        vectorStore.add(chunks);
        ingestionCounter.increment(chunks.size());

        log.info("[RagService] Ingested {} chunk(s), source={}", chunks.size(), source);
        return parentDoc.getId();
    }

    /**
     * Retrieve top-K semantically similar documents for a query.
     *
     * Strategy:
     *  1. Request topK*2 candidates from vector store with similarityThreshold filter.
     *  2. Record how many candidates were filtered out (candidates - returned).
     *  3. Limit final result to topK.
     */
    public List<Document> retrieve(String query, int topK) {
        aiAvailabilityChecker.ensureConfigured();

        int candidateCount = topK * 2;
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(candidateCount)
                .similarityThreshold(similarityThreshold)
                .build();

        List<Document> results = vectorStore.similaritySearch(request);
        int filteredOut = candidateCount - results.size();
        filteredCountSummary.record(filteredOut);

        if (results.isEmpty()) {
            retrievalMissCounter.increment();
        } else {
            retrievalHitCounter.increment();
        }

        List<Document> limited = results.stream().limit(topK).toList();
        log.info("[RagService] Retrieved {}/{} docs (threshold={}, filtered={}), query='{}'",
                limited.size(), candidateCount, similarityThreshold, filteredOut, query);
        return limited;
    }

    /** Build an augmented context string from retrieved documents. */
    public String buildContext(String query) {
        List<Document> docs = retrieve(query, defaultTopK);
        if (docs.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("Relevant context:\n");
        for (int i = 0; i < docs.size(); i++) {
            sb.append(String.format("[%d] %s\n", i + 1, docs.get(i).getText()));
        }
        return sb.toString();
    }
}
```

- [ ] **Step 2: 运行测试，确认全部通过**

```bash
./mvnw test -Dtest=RagServiceTest -q 2>&1 | tail -20
```

Expected:
```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] **Step 3: 确认全套测试不受影响**

```bash
./mvnw test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`，无 FAIL。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dawn/ai/service/RagService.java \
        src/test/java/com/dawn/ai/service/RagServiceTest.java
git commit -m "feat: add TokenTextSplitter chunking and similarity threshold to RagService"
```

---

## Task 4：验收检查

- [ ] **Step 1: 确认 Prometheus 指标已注册**

启动应用后（需要 docker-compose up -d postgres redis 和 OPENAI_API_KEY）：

```bash
curl -s http://localhost:8080/actuator/prometheus | grep ai_rag
```

Expected 输出中包含：
```
ai_rag_ingestion_total_total
ai_rag_retrieval_total_total{result="hit",...}
ai_rag_retrieval_total_total{result="miss",...}
ai_rag_retrieval_filtered_count_count
ai_rag_retrieval_filtered_count_sum
```

- [ ] **Step 2: 手动测试 ingest 接口（可选，需要运行中的应用）**

```bash
# 先入库一段短文本
curl -s -X POST http://localhost:8080/api/v1/rag/ingest \
  -H "Content-Type: application/json" \
  -d '{"content":"Dawn AI月费为99元，年费为888元，支持免费试用7天。","source":"pricing","category":"billing"}'

# 搜索
curl -s "http://localhost:8080/api/v1/rag/search?query=月费多少&topK=3" | jq .
```

Expected: 返回包含"99元"的文档。

- [ ] **Step 3: 最终 commit（若 Task 4 有任何小修复）**

```bash
git add -A
git commit -m "chore: rag phase1 verification and minor fixes"
```

---

## 完成标准

- [ ] `RagServiceTest` 8 个用例全部 GREEN
- [ ] `./mvnw test` 全套通过
- [ ] Prometheus 端点暴露 `ai.rag.retrieval.filtered_count`
- [ ] `application.yml` 有 `app.ai.rag.similarity-threshold` 和 `default-top-k` 两个配置项
