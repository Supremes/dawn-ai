package com.dawn.ai.service;

import com.dawn.ai.config.AiAvailabilityChecker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock private VectorStore vectorStore;
    @Mock private AiAvailabilityChecker aiAvailabilityChecker;
    @Mock private HydeQueryExpander hydeQueryExpander;

    private SimpleMeterRegistry meterRegistry;
    private RagService ragService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ragService = new RagService(vectorStore, meterRegistry, aiAvailabilityChecker, hydeQueryExpander);
        // 注入配置值（与 application.yml 一致）
        ragService.setSimilarityThreshold(0.7);
        ragService.setDefaultTopK(5);
        // @PostConstruct 在直接 new 时不自动执行，手动初始化指标
        ragService.initMetrics();
        lenient().when(hydeQueryExpander.expand(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
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
        // 生成约 1000 tokens 的文本（英文约 1 word/token）
        String longContent = "word ".repeat(600);
        ragService.ingest(longContent, "doc", "manual");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertThat(captor.getValue().size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("ingest: 每个 chunk 应继承父文档的 source 和 category metadata")
    void ingest_chunksInheritMetadata() {
        // Use long text to ensure multiple chunks are produced
        String content = "word ".repeat(600);
        ragService.ingest(content, "pricing-doc", "billing");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertThat(captor.getValue()).isNotEmpty();
        assertThat(captor.getValue()).allSatisfy(chunk -> {
            assertThat(chunk.getMetadata()).containsEntry("source", "pricing-doc");
            assertThat(chunk.getMetadata()).containsEntry("category", "billing");
        });
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
        assertThat(req.getQuery()).isEqualTo("test query");
        assertThat(req.getSimilarityThreshold()).isCloseTo(0.7, within(1e-9));
    }

    @Test
    @DisplayName("retrieve: HyDE 启用时应使用扩展后的检索查询")
    void retrieve_usesExpandedQueryWhenHydeEnabled() {
        when(hydeQueryExpander.expand("月费多少")).thenReturn("Dawn AI 的月费价格、订阅方案、计费规则与套餐说明");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        ragService.retrieve("月费多少", 5);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getQuery()).isEqualTo("Dawn AI 的月费价格、订阅方案、计费规则与套餐说明");
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
