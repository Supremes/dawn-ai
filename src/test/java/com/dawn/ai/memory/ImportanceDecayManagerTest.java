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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportanceDecayManagerTest {

    private MemoryRepository memoryRepository;
    private ImportanceDecayManager manager;

    // halfLifeDays=30, minImportance=0.01, batchSize=500
    @BeforeEach
    void setUp() {
        memoryRepository = mock(MemoryRepository.class);
        manager = new ImportanceDecayManager(memoryRepository, 30.0, 0.01, 500);
    }

    private static MemoryEntity candidate(double importance, long lastAccessedMs) {
        MemoryEntity entity = new MemoryEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId("user-1");
        entity.setContent("content");
        entity.setMemoryType(MemoryType.EPISODIC);
        entity.setImportance(importance);
        Instant ts = Instant.ofEpochMilli(lastAccessedMs);
        entity.setCreatedAt(ts);
        entity.setUpdatedAt(ts);
        entity.setLastAccessedAt(ts);
        return entity;
    }

    // ── computeDecay unit tests ──────────────────────────────────────────────

    @Test
    void computeDecay_halvesImportanceAfterOneHalfLife() {
        long now = Instant.now().toEpochMilli();
        long thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli();

        double result = manager.computeDecay(0.5, thirtyDaysAgo, now);

        // After exactly one half-life, importance should be ~0.25 (halved)
        assertThat(result).isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void computeDecay_respectsMinImportanceFloor() {
        long now = Instant.now().toEpochMilli();
        long veryOld = Instant.now().minus(1000, ChronoUnit.DAYS).toEpochMilli();

        double result = manager.computeDecay(0.5, veryOld, now);

        assertThat(result).isEqualTo(0.01); // clamped to minImportance
    }

    @Test
    void computeDecay_noDecayForFutureOrNowTimestamp() {
        long now = Instant.now().toEpochMilli();

        assertThat(manager.computeDecay(0.5, now, now)).isEqualTo(0.5);
        assertThat(manager.computeDecay(0.5, now + 1000, now)).isEqualTo(0.5); // future
    }

    @Test
    void computeDecay_noDecayForRecentlyAccessedDoc() {
        long now = Instant.now().toEpochMilli();
        long oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS).toEpochMilli();

        double result = manager.computeDecay(0.5, oneHourAgo, now);

        // 1 hour / 30-day half-life → negligible decay, still very close to 0.5
        assertThat(result).isGreaterThan(0.499);
    }

    // ── decay() integration tests (repository mocked) ─────────────────────────

    @Test
    void decay_appliesDecayToStaleDocuments() {
        long oldTs = Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli(); // one half-life
        MemoryEntity stale = candidate(0.5, oldTs);

        when(memoryRepository.findDecayCandidates(any(Pageable.class)))
                .thenReturn(List.of(stale));

        manager.decay();

        // importance should be reduced (delta ≈ 0.25 >> MIN_DELTA)
        verify(memoryRepository).updateImportance(eq(stale.getId()),
                doubleThat(v -> v < 0.5));
    }

    @Test
    void decay_skipsDocumentsWithInsignificantDelta() {
        long justNow = Instant.now().minus(1, ChronoUnit.HOURS).toEpochMilli();
        // 1 hour / 30-day half-life → delta << MIN_DELTA (0.001)
        MemoryEntity fresh = candidate(0.5, justNow);

        when(memoryRepository.findDecayCandidates(any(Pageable.class)))
                .thenReturn(List.of(fresh));

        manager.decay();

        verify(memoryRepository, never()).updateImportance(any(UUID.class), anyDouble());
    }

    @Test
    void decay_noOpsWhenNoCandidates() {
        when(memoryRepository.findDecayCandidates(any(Pageable.class)))
                .thenReturn(List.of());

        manager.decay();

        verify(memoryRepository, never()).updateImportance(any(UUID.class), anyDouble());
    }

    @Test
    void decay_processesMixedBatch() {
        long oldTs   = Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli(); // needs decay
        long freshTs = Instant.now().minus(1,  ChronoUnit.HOURS).toEpochMilli(); // skip

        MemoryEntity old   = candidate(0.5, oldTs);
        MemoryEntity fresh = candidate(0.5, freshTs);

        when(memoryRepository.findDecayCandidates(any(Pageable.class)))
                .thenReturn(List.of(old, fresh));

        manager.decay();

        // Only the stale document is updated
        verify(memoryRepository).updateImportance(eq(old.getId()), anyDouble());
        verify(memoryRepository, never()).updateImportance(eq(fresh.getId()), anyDouble());
    }
}
