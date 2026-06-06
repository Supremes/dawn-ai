package com.dawn.ai.memory;

import com.dawn.ai.memory.entity.MemoryEntity;
import com.dawn.ai.memory.repository.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EvictionPolicyManagerTest {

    private MemoryManager memoryManager;
    private MemoryRepository memoryRepository;
    private EvictionPolicyManager manager;

    @BeforeEach
    void setUp() {
        memoryManager = mock(MemoryManager.class);
        memoryRepository = mock(MemoryRepository.class);
        manager = new EvictionPolicyManager(memoryManager, memoryRepository, 0.1, 180);
    }

    private static MemoryEntity staleEntity() {
        MemoryEntity entity = new MemoryEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId("user-1");
        entity.setContent("old content");
        entity.setMemoryType(MemoryType.EPISODIC);
        entity.setImportance(0.05);
        Instant old = Instant.now().minus(200, ChronoUnit.DAYS);
        entity.setCreatedAt(old);
        entity.setUpdatedAt(old);
        entity.setLastAccessedAt(old);
        return entity;
    }

    @Test
    void evict_deletesLowImportanceOldDocuments() {
        MemoryEntity stale = staleEntity();
        when(memoryRepository.findEvictionCandidates(anyDouble(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(stale));

        manager.evict();

        verify(memoryManager).delete(stale.getId().toString());
    }

    @Test
    void evict_keepsHighImportanceDocumentsEvenIfOld() {
        // JPA query filters out high-importance docs (importance < threshold) before returning
        when(memoryRepository.findEvictionCandidates(anyDouble(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        manager.evict();

        verify(memoryManager, never()).delete(anyString());
    }

    @Test
    void evict_keepsRecentDocumentsEvenIfLowImportance() {
        // JPA query filters out recent docs (createdAt < cutoff) before returning
        when(memoryRepository.findEvictionCandidates(anyDouble(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        manager.evict();

        verify(memoryManager, never()).delete(anyString());
    }

    @Test
    void evict_continuesWhenSingleDeleteFails() {
        MemoryEntity stale = staleEntity();
        when(memoryRepository.findEvictionCandidates(anyDouble(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(stale));
        when(memoryManager.delete(anyString())).thenThrow(new RuntimeException("delete failed"));

        // Per-entity failures are caught; evict() should not propagate
        manager.evict();

        verify(memoryManager).delete(stale.getId().toString());
    }
}
