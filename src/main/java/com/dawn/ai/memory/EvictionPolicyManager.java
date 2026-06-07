package com.dawn.ai.memory;

import com.dawn.ai.memory.entity.MemoryEntity;
import com.dawn.ai.memory.repository.MemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
public class EvictionPolicyManager {

    private final MemoryManager memoryManager;
    private final MemoryRepository memoryRepository;
    private final double importanceThreshold;
    private final int maxAgeDays;
    private static final int EVICTION_BATCH = 500;

    public EvictionPolicyManager(
            MemoryManager memoryManager,
            MemoryRepository memoryRepository,
            @Value("${app.memory.eviction.importance-threshold:0.1}") double importanceThreshold,
            @Value("${app.memory.eviction.max-age-days:180}") int maxAgeDays) {
        this.memoryManager = memoryManager;
        this.memoryRepository = memoryRepository;
        this.importanceThreshold = importanceThreshold;
        this.maxAgeDays = maxAgeDays;
    }

    @Scheduled(cron = "${app.memory.eviction.cron:0 0 3 * * ?}")
    public void evict() {
        Instant cutoff = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS);

        List<MemoryEntity> candidates = memoryRepository.findEvictionCandidates(
                importanceThreshold, cutoff, PageRequest.of(0, EVICTION_BATCH));

        if (candidates.isEmpty()) {
            log.debug("[EvictionPolicyManager] No documents to evict");
            return;
        }

        for (MemoryEntity entity : candidates) {
            try {
                memoryManager.delete(entity.getId().toString());
            } catch (Exception e) {
                log.warn("[EvictionPolicyManager] Failed to evict memory={}: {}", entity.getId(), e.getMessage());
            }
        }

        log.info("[EvictionPolicyManager] Evicted {} documents (importance<{}, age>{}d)",
                candidates.size(), importanceThreshold, maxAgeDays);
    }
}
