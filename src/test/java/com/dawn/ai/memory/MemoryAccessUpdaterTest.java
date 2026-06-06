package com.dawn.ai.memory;

import com.dawn.ai.memory.repository.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class MemoryAccessUpdaterTest {

    private MemoryRepository memoryRepository;
    private MemoryAccessUpdater updater;

    @BeforeEach
    void setUp() {
        memoryRepository = mock(MemoryRepository.class);
        updater = new MemoryAccessUpdater(memoryRepository);
    }

    @Test
    void updateAccessTime_updatesOnlyMemoryDocs() {
        String memId = UUID.randomUUID().toString();
        Document memoryDoc = new Document(memId, "summary text",
                Map.of("type", "summary", "importance", 0.5));
        Document ragDoc = new Document(UUID.randomUUID().toString(), "rag chunk",
                Map.of("source", "manual", "category", "general"));

        updater.updateAccessTime(List.of(memoryDoc, ragDoc));

        // Only the doc carrying 'type' metadata is treated as a memory doc
        verify(memoryRepository).updateLastAccessedAt(
                argThat((List<UUID> ids) -> ids.size() == 1 && ids.contains(UUID.fromString(memId))),
                any(Instant.class));
    }

    @Test
    void updateAccessTime_noOpsWhenAllRagDocs() {
        Document ragDoc = new Document(UUID.randomUUID().toString(), "chunk",
                Map.of("source", "manual"));

        updater.updateAccessTime(List.of(ragDoc));

        verify(memoryRepository, never()).updateLastAccessedAt(anyList(), any(Instant.class));
    }

    @Test
    void updateAccessTime_noOpsForEmptyList() {
        updater.updateAccessTime(List.of());

        verify(memoryRepository, never()).updateLastAccessedAt(anyList(), any(Instant.class));
    }

    @Test
    void updateAccessTime_handlesRepositoryFailureGracefully() {
        Document memoryDoc = new Document(UUID.randomUUID().toString(), "summary",
                Map.of("type", "reflection", "importance", 0.9));
        when(memoryRepository.updateLastAccessedAt(anyList(), any(Instant.class)))
                .thenThrow(new RuntimeException("DB error"));

        // Should not propagate exception
        updater.updateAccessTime(List.of(memoryDoc));
    }

    @Test
    void updateAccessTime_updatesMultipleMemoryDocs() {
        Document summary    = new Document(UUID.randomUUID().toString(), "s", Map.of("type", "summary"));
        Document reflection = new Document(UUID.randomUUID().toString(), "r", Map.of("type", "reflection"));
        Document rag        = new Document(UUID.randomUUID().toString(), "k", Map.of("source", "file"));

        updater.updateAccessTime(List.of(summary, reflection, rag));

        verify(memoryRepository).updateLastAccessedAt(
                argThat((List<UUID> ids) -> ids.size() == 2),
                any(Instant.class));
    }
}
