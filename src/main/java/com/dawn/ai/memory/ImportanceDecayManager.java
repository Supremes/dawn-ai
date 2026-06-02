package com.dawn.ai.memory;

import com.dawn.ai.memory.entity.MemoryEntity;
import com.dawn.ai.memory.repository.MemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
public class ImportanceDecayManager {

    private static final double LN2 = Math.log(2);
    private static final double MIN_DELTA = 0.001;

    private final MemoryRepository memoryRepository;
    private final double halfLifeDays;
    private final double minImportance;
    private final int batchSize;

    public ImportanceDecayManager(
            MemoryRepository memoryRepository,
            @Value("${app.memory.decay.half-life-days:30}") double halfLifeDays,
            @Value("${app.memory.decay.min-importance:0.01}") double minImportance,
            @Value("${app.memory.decay.batch-size:500}") int batchSize) {
        this.memoryRepository = memoryRepository;
        this.halfLifeDays = halfLifeDays;
        this.minImportance = minImportance;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${app.memory.decay.cron:0 30 3 * * ?}")
    @Transactional
    public void decay() {
        Instant now = Instant.now();
        long nowMs = now.toEpochMilli();

        List<MemoryEntity> candidates = memoryRepository.findDecayCandidates(
                PageRequest.of(0, batchSize));

        int updated = 0;
        for (MemoryEntity entity : candidates) {
            long lastAccessedMs = entity.getLastAccessedAt() != null
                    ? entity.getLastAccessedAt().toEpochMilli()
                    : entity.getCreatedAt().toEpochMilli();

            double decayed = computeDecay(entity.getImportance(), lastAccessedMs, nowMs);
            if (Math.abs(decayed - entity.getImportance()) >= MIN_DELTA) {
                memoryRepository.updateImportance(entity.getId(), decayed);
                updated++;
            }
        }

        if (updated == 0) {
            log.debug("[ImportanceDecayManager] No documents require importance decay");
            return;
        }
        log.info("[ImportanceDecayManager] Decayed importance for {} documents (halfLife={}d, min={})",
                updated, halfLifeDays, minImportance);
    }

    double computeDecay(double currentImportance, long lastAccessedAtMs, long nowMs) {
        double daysSinceAccess = (nowMs - lastAccessedAtMs) / 86_400_000.0;
        if (daysSinceAccess <= 0) {
            return currentImportance;
        }
        double decayed = currentImportance * Math.exp(-LN2 * daysSinceAccess / halfLifeDays);
        return Math.max(minImportance, decayed);
    }
}
