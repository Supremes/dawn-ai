package com.dawn.ai.service;

import com.dawn.ai.config.AiAvailabilityChecker;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.message.TextBlock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock private AiAvailabilityChecker aiAvailabilityChecker;
    @Mock private Knowledge knowledge;
    @Mock private QueryRewriter queryRewriter;

    private SimpleMeterRegistry meterRegistry;
    private RagService ragService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ragService = new RagService(meterRegistry, aiAvailabilityChecker, knowledge, queryRewriter);
        lenient().when(knowledge.addDocuments(anyList())).thenReturn(Mono.empty());
        lenient().when(queryRewriter.rewrite(anyString())).thenAnswer(inv -> inv.getArgument(0));
        ragService.setSimilarityThreshold(0.7);
        ragService.setDefaultTopK(5);
        ragService.setFallbackSimilarityThreshold(0.5);
        ragService.setShortQueryMaxLength(12);
        ragService.initMetrics();
    }

    // ── ingest tests ────────────────────────────────────────────

    @Test
    @DisplayName("ingest: short content stores chunks via Knowledge")
    void ingest_shortContent_storesViaKnowledge() {
        String shortContent = "Dawn AI is an intelligent assistant.";
        ragService.ingest(shortContent, "test", "general");

        verify(knowledge).addDocuments(anyList());
    }

    @Test
    @DisplayName("ingest: long content splits into multiple chunks")
    @SuppressWarnings("unchecked")
    void ingest_longContent_storesMultipleChunks() {
        String longContent = "word ".repeat(600);
        ragService.ingest(longContent, "doc", "manual");

        ArgumentCaptor<List<Document>> captor =
                (ArgumentCaptor<List<Document>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(knowledge).addDocuments(captor.capture());
        assertThat(captor.getValue().size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("ingest: chunks inherit source and category in payload")
    @SuppressWarnings("unchecked")
    void ingest_chunksInheritMetadata() {
        String content = "word ".repeat(600);
        ragService.ingest(content, "pricing-doc", "billing");

        ArgumentCaptor<List<Document>> captor =
                (ArgumentCaptor<List<Document>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(knowledge).addDocuments(captor.capture());
        assertThat(captor.getValue()).isNotEmpty();
        assertThat(captor.getValue()).allSatisfy(doc -> {
            var payload = doc.getMetadata().getPayload();
            assertThat(payload).containsEntry("source", "pricing-doc");
            assertThat(payload).containsEntry("category", "billing");
        });
    }

    // ── retrieve tests ──────────────────────────────────────────

    @Test
    @DisplayName("retrieve: applies query rewrite before retrieval")
    void retrieve_appliesQueryRewrite() {
        ragService.setShortQueryMaxLength(3);
        when(queryRewriter.rewrite("月费多少")).thenReturn("Dawn AI 定价 月费");
        when(knowledge.retrieve(anyString(), any(RetrieveConfig.class)))
                .thenReturn(Mono.just(List.of()));

        ragService.retrieve("月费多少", 5);

        verify(queryRewriter).rewrite("月费多少");
        verify(knowledge).retrieve(eq("Dawn AI 定价 月费"), any(RetrieveConfig.class));
    }

    @Test
    @DisplayName("retrieve: empty result increments miss counter")
    void retrieve_emptyResult_incrementsMissCounter() {
        when(knowledge.retrieve(anyString(), any(RetrieveConfig.class)))
                .thenReturn(Mono.just(List.of()));

        ragService.retrieve("query", 5);

        double missCount = meterRegistry.counter("ai.rag.retrieval.total", "result", "miss").count();
        assertThat(missCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("retrieve: non-empty result increments hit counter")
    void retrieve_nonEmptyResult_incrementsHitCounter() {
        Document doc = new Document(DocumentMetadata.builder()
                .content(TextBlock.builder().text("content").build())
                .docId("1")
                .chunkId("0")
                .build());
        when(knowledge.retrieve(anyString(), any(RetrieveConfig.class)))
                .thenReturn(Mono.just(List.of(doc)));

        ragService.retrieve("query", 5);

        double hitCount = meterRegistry.counter("ai.rag.retrieval.total", "result", "hit").count();
        assertThat(hitCount).isEqualTo(1.0);
    }

    @Test
    @DisplayName("retrieve: uses configured topK and similarity threshold")
    void retrieve_usesConfiguredParameters() {
        when(knowledge.retrieve(anyString(), any(RetrieveConfig.class)))
                .thenReturn(Mono.just(List.of()));

        ragService.retrieve("a much longer query that should not trigger fallback", 10);

        ArgumentCaptor<RetrieveConfig> captor = ArgumentCaptor.forClass(RetrieveConfig.class);
        verify(knowledge).retrieve(anyString(), captor.capture());
        assertThat(captor.getValue().getLimit()).isEqualTo(10);
        assertThat(captor.getValue().getScoreThreshold()).isEqualTo(0.7);
    }

    @Test
    @DisplayName("retrieve: short query misses primary threshold then retries with fallback threshold")
    void retrieve_shortQueryRetriesWithFallbackThreshold() {
        Document doc = new Document(DocumentMetadata.builder()
                .content(TextBlock.builder().text("杜康今日眼睛很疼").build())
                .docId("1")
                .chunkId("0")
                .build());

        when(knowledge.retrieve(eq("杜康"), any(RetrieveConfig.class)))
                .thenAnswer(invocation -> {
                    RetrieveConfig config = invocation.getArgument(1);
                    if (config.getScoreThreshold() >= 0.7) {
                        return Mono.just(List.of());
                    }
                    return Mono.just(List.of(doc));
                });

        List<Document> result = ragService.retrieve("杜康", 5);

        assertThat(result).containsExactly(doc);

        ArgumentCaptor<RetrieveConfig> captor = ArgumentCaptor.forClass(RetrieveConfig.class);
        verify(knowledge, times(2)).retrieve(eq("杜康"), captor.capture());
        assertThat(captor.getAllValues())
                .extracting(RetrieveConfig::getScoreThreshold)
                .containsExactly(0.7, 0.5);
    }
}
