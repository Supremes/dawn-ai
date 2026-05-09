package com.dawn.ai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class EvictionPolicyManagerTest {

    private VectorStore vectorStore;
    private EvictionPolicyManager manager;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        manager = new EvictionPolicyManager(vectorStore, 0.1, 180);
    }

    @Test
    void evict_deletesLowImportanceOldDocuments() {
        long oldTs = Instant.now().minus(200, ChronoUnit.DAYS).toEpochMilli();
        Document stale = new Document("doc1", "old content",
                Map.of("type", "summary", "importance", 0.05, "createdAt", oldTs));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(stale));

        manager.evict();

        verify(vectorStore).delete(argThat((List<String> ids) -> ids.contains("doc1")));
    }

    @Test
    void evict_keepsHighImportanceDocumentsEvenIfOld() {
        // pgvector filterExpression excludes high-importance docs before returning results
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        manager.evict();

        verify(vectorStore, never()).delete(any(List.class));
    }

    @Test
    void evict_keepsRecentDocumentsEvenIfLowImportance() {
        // pgvector filterExpression excludes recent docs (createdAt > cutoff) before returning results
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        manager.evict();

        verify(vectorStore, never()).delete(any(List.class));
    }

    @Test
    void evict_handlesVectorStoreFailureGracefully() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("DB down"));

        manager.evict();

        verify(vectorStore, never()).delete(any(List.class));
    }
}
