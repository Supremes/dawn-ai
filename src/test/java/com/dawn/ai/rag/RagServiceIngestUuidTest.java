package com.dawn.ai.rag;

import com.dawn.ai.config.AiAvailabilityChecker;
import com.dawn.ai.rag.ingestion.OverlapTextSplitter;
import com.dawn.ai.rag.retrieval.RetrievalRouter;
import com.dawn.ai.rag.retrieval.fusion.ReciprocalRankFusion;
import com.dawn.ai.rag.retrieval.rerank.HeuristicRetrievalReranker;
import com.dawn.ai.rag.retrieval.sparse.SparseRetriever;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RagServiceIngestUuidTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private AiAvailabilityChecker aiAvailabilityChecker;

    @Mock
    private SparseRetriever sparseRetriever;

    private ExecutorService ragRetrievalExecutor;
    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragRetrievalExecutor = Executors.newFixedThreadPool(2);
        ragService = new RagService(
                vectorStore,
                new SimpleMeterRegistry(),
                aiAvailabilityChecker,
                new HeuristicRetrievalReranker(),
                sparseRetriever,
                new ReciprocalRankFusion(),
                new RetrievalRouter(),
                new OverlapTextSplitter(4, 2),
                ragRetrievalExecutor);
        ragService.initMetrics();
    }

    @AfterEach
    void tearDown() {
        ragRetrievalExecutor.shutdownNow();
    }

    @Test
    @DisplayName("ingest: 多 chunk 写入时应使用 UUID 兼容的 chunk id，并保留 docId metadata")
    void ingest_usesUuidCompatibleChunkIdsAndPreservesDocIdMetadata() {
        String docId = ragService.ingest("one two three four five six seven", "pricing-doc", "billing");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertThat(UUID.fromString(docId)).isNotNull();
        assertThat(captor.getValue()).hasSize(3).allSatisfy(chunk -> {
            assertThat(UUID.fromString(chunk.getId())).isNotNull();
            assertThat(chunk.getMetadata()).containsEntry("docId", docId);
            assertThat(chunk.getMetadata()).containsEntry("parentDocumentId", docId);
            assertThat(chunk.getMetadata()).containsEntry("source", "pricing-doc");
            assertThat(chunk.getMetadata()).containsEntry("category", "billing");
        });
    }
}
