package com.dawn.ai.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
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
    private static final int EVICTION_BATCH = 500;

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

        // Push eviction conditions to pgvector as metadata filters (SQL WHERE clause),
        // so only genuinely stale docs are fetched — no in-memory post-filtering needed.
        // Limitation: results are still similarity-ranked by the probe query; documents
        // semantically far from it may not surface if total stale count > EVICTION_BATCH.
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        var filter = fb.and(
                fb.and(
                        fb.ne("type", "reflection"),
                        fb.lt("importance", importanceThreshold)
                ),
                fb.lt("createdAt", cutoffMs)
        ).build();

        List<Document> candidates;
        try {
            candidates = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(EVICTION_PROBE_QUERY)
                            .topK(EVICTION_BATCH)
                            .similarityThreshold(0.0)
                            .filterExpression(filter)
                            .build());
        } catch (Exception e) {
            log.warn("[EvictionPolicyManager] Failed to fetch eviction candidates: {}", e.getMessage());
            return;
        }

        List<String> toDelete = candidates.stream()
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
}
