package com.dawn.ai.rag;

import com.dawn.ai.config.AiAvailabilityChecker;
import com.dawn.ai.rag.retrieval.rerank.HeuristicRetrievalReranker;
import com.dawn.ai.rag.retrieval.fusion.ReciprocalRankFusion;
import com.dawn.ai.rag.retrieval.RetrievalRequest;
import com.dawn.ai.rag.retrieval.RetrievalRouter;
import com.dawn.ai.rag.retrieval.sparse.SparseRetriever;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock private VectorStore vectorStore;
    @Mock private AiAvailabilityChecker aiAvailabilityChecker;
    @Mock private SparseRetriever sparseRetriever;

    private SimpleMeterRegistry meterRegistry;
    private RagService ragService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ragService = new RagService(
                vectorStore,
                meterRegistry,
                aiAvailabilityChecker,
                new HeuristicRetrievalReranker(),
                sparseRetriever,
                new ReciprocalRankFusion(),
                new RetrievalRouter());
        // 注入配置值（与 application.yml 一致）
        ragService.setSimilarityThreshold(0.7);
        ragService.setDefaultTopK(5);
        ragService.setHybridEnabled(false);
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

    @Test
    @DisplayName("ingest: 自定义 overlap 生效，后续 chunk 应包含前一个 chunk 的尾部 token")
    void ingest_configuredOverlapProducesOverlappingChunks() {
        ragService.setChunkSize(4);
        ragService.setChunkOverlap(2);
        ragService.initSplitter();

        ragService.ingest("one two three four five six seven", "doc", "manual");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertThat(captor.getValue()).extracting(Document::getText)
                .containsExactly(
                        "one two three four",
                        "three four five six",
                        "five six seven"
                );
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
        assertThat(req.getSimilarityThreshold()).isCloseTo(0.7, within(1e-9));
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

    @Test
    @DisplayName("retrieve: metadata filters 存在时应构建 filterExpression")
    void retrieve_withMetadataFilters_buildsFilterExpression() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        ragService.retrieve(RetrievalRequest.builder()
                .query("refund policy")
                .topK(5)
                .metadataFilters(Map.of(
                        "source", List.of("pricing-doc"),
                        "category", List.of("billing")
                ))
                .build());

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        SearchRequest request = captor.getValue();
        assertThat(request.hasFilterExpression()).isTrue();
        assertThat(request.getFilterExpression().toString())
                .contains("source")
                .contains("pricing-doc")
                .contains("category")
                .contains("billing");
    }

    @Test
    @DisplayName("retrieve: rerank 应把更匹配 query 的文档排到前面")
    void retrieve_reranksCandidatesBeforeLimiting() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("天气很好，适合出门"),
                new Document("Dawn AI refund policy and refund steps")
        ));

        List<Document> result = ragService.retrieve(RetrievalRequest.builder()
                .query("refund policy")
                .topK(1)
                .build());

        assertThat(result).extracting(Document::getText)
                .containsExactly("Dawn AI refund policy and refund steps");
    }

    @Test
    @DisplayName("retrieve: hybrid search 应融合 dense 与 BM25 结果")
    void retrieve_hybridSearchFusesDenseAndSparseResults() {
        Document weather = new Document("doc-1", "天气很好", Map.of());
        Document refund = new Document("doc-2", "refund policy details", Map.of());
        Document invoice = new Document("doc-3", "refund invoice steps", Map.of());
        ragService.setHybridEnabled(true);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(weather, refund));
        when(sparseRetriever.retrieve(any(RetrievalRequest.class), anyInt())).thenReturn(List.of(refund, invoice));

        List<Document> result = ragService.retrieve(RetrievalRequest.builder()
                .query("refund policy")
                .topK(2)
                .rerankEnabled(false)
                .build());

        assertThat(result).extracting(Document::getId)
                .containsExactly("doc-2", "doc-1");
    }

    @Test
    @DisplayName("retrieve: 路由到 dense 时不应调用 sparse retriever")
    void retrieve_denseRouteDoesNotCallSparseRetriever() {
        ragService.setHybridEnabled(true);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("doc-1", "说明文档", Map.of())));

        ragService.retrieve(RetrievalRequest.builder()
                .query("请解释 Dawn AI 的退款流程和注意事项")
                .topK(1)
                .rerankEnabled(false)
                .build());

        verify(sparseRetriever, never()).retrieve(any(RetrievalRequest.class), anyInt());
    }
}
