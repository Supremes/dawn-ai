package com.dawn.ai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
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
        when(vectorStore.similaritySearch(any())).thenReturn(List.of(stale));

        manager.evict();

        verify(vectorStore).delete(argThat(ids -> ids.contains("doc1")));
    }

    @Test
    void evict_keepsHighImportanceDocumentsEvenIfOld() {
        long oldTs = Instant.now().minus(200, ChronoUnit.DAYS).toEpochMilli();
        Document important = new Document("doc2", "important content",
                Map.of("type", "summary", "importance", 0.9, "createdAt", oldTs));
        when(vectorStore.similaritySearch(any())).thenReturn(List.of(important));

        manager.evict();

        verify(vectorStore, never()).delete(any());
    }

    @Test
    void evict_keepsRecentDocumentsEvenIfLowImportance() {
        long recentTs = Instant.now().minus(10, ChronoUnit.DAYS).toEpochMilli();
        Document recent = new Document("doc3", "recent content",
                Map.of("type", "summary", "importance", 0.05, "createdAt", recentTs));
        when(vectorStore.similaritySearch(any())).thenReturn(List.of(recent));

        manager.evict();

        verify(vectorStore, never()).delete(any());
    }

    @Test
    void evict_handlesVectorStoreFailureGracefully() {
        when(vectorStore.similaritySearch(any())).thenThrow(new RuntimeException("DB down"));

        manager.evict();

        verify(vectorStore, never()).delete(any());
    }
}
