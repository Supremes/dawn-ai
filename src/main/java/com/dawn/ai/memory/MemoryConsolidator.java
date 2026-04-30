package com.dawn.ai.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class MemoryConsolidator {

    private final VectorStore vectorStore;
    private final ApplicationEventPublisher eventPublisher;
    private final int reflectionThreshold;

    private final ConcurrentHashMap<String, AtomicInteger> consolidationCount = new ConcurrentHashMap<>();

    public MemoryConsolidator(VectorStore vectorStore,
                               ApplicationEventPublisher eventPublisher,
                               @Value("${app.memory.consolidation.reflection-threshold:10}") int reflectionThreshold) {
        this.vectorStore = vectorStore;
        this.eventPublisher = eventPublisher;
        this.reflectionThreshold = reflectionThreshold;
    }

    @EventListener
    @Async
    public void onConsolidationRequest(ConsolidationRequestEvent event) {
        SummaryResult result = event.result();
        Document doc = new Document(
                UUID.randomUUID().toString(),
                result.text(),
                Map.of(
                        "type", "summary",
                        "sessionId", result.sessionId(),
                        "importance", result.importanceScore(),
                        "createdAt", result.createdAt().toEpochMilli(),
                        "lastAccessedAt", result.createdAt().toEpochMilli()
                )
        );
        try {
            vectorStore.add(List.of(doc));
            log.info("[MemoryConsolidator] Persisted summary for session={}, importance={}", result.sessionId(), result.importanceScore());
        } catch (Exception e) {
            log.warn("[MemoryConsolidator] VectorStore write failed session={}: {}", result.sessionId(), e.getMessage());
            return;
        }

        AtomicInteger counter = consolidationCount.computeIfAbsent(result.sessionId(), k -> new AtomicInteger());
        int count = counter.incrementAndGet();
        if (count >= reflectionThreshold && counter.compareAndSet(count, 0)) {
            eventPublisher.publishEvent(new ReflectionRequestEvent(result.sessionId()));
        }
    }
}
