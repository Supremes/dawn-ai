package com.dawn.ai.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
public class EvictionPolicyManager {

    private final VectorStore vectorStore;
    private final double importanceThreshold;
    private final int maxAgeDays;

    private static final String EVICTION_PROBE_QUERY = "对话历史摘要";
    private static final int EVICTION_BATCH = 100;

    public EvictionPolicyManager(
            VectorStore vectorStore,
            @Value("${app.memory.eviction.importance-threshold:0.1}") double importanceThreshold,
            @Value("${app.memory.eviction.max-age-days:180}") int maxAgeDays) {
        this.vectorStore = vectorStore;
        this.importanceThreshold = importanceThreshold;
        this.maxAgeDays = maxAgeDays;
    }

    @Scheduled(cron = "${app.memory.eviction.cron:0 0 3 * * ?}")
    public void evict() {
        long cutoffMs = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS).toEpochMilli();
        List<Document> candidates;
        try {
            candidates = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(EVICTION_PROBE_QUERY)
                            .topK(EVICTION_BATCH)
                            .similarityThreshold(0.0)
                            .build());
        } catch (Exception e) {
            log.warn("[EvictionPolicyManager] Failed to fetch eviction candidates: {}", e.getMessage());
            return;
        }

        List<String> toDelete = candidates.stream()
                .filter(doc -> isStale(doc, cutoffMs))
                .map(Document::getId)
                .toList();

        if (toDelete.isEmpty()) {
            log.debug("[EvictionPolicyManager] No documents to evict");
            return;
        }
        vectorStore.delete(toDelete);
        log.info("[EvictionPolicyManager] Evicted {} documents (importance<{}, age>{}d)",
                toDelete.size(), importanceThreshold, maxAgeDays);
    }

    private boolean isStale(Document doc, long cutoffMs) {
        if ("reflection".equals(doc.getMetadata().get("type"))) return false;
        Object imp = doc.getMetadata().get("importance");
        Object ts = doc.getMetadata().get("createdAt");
        double importance = imp instanceof Number n ? n.doubleValue() : 1.0;
        long createdAt = ts instanceof Number n ? n.longValue() : Long.MAX_VALUE;
        return importance < importanceThreshold && createdAt < cutoffMs;
    }
}
