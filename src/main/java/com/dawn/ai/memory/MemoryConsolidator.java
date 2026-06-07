package com.dawn.ai.memory;

import com.dawn.ai.memory.event.EpisodicMemoryEvent;
import com.dawn.ai.memory.event.FactsExtractedEvent;
import com.dawn.ai.memory.event.ReflectionRequestEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class MemoryConsolidator {

    private final MemoryManager memoryManager;
    private final ApplicationEventPublisher eventPublisher;
    private final int reflectionThreshold;

    private final ConcurrentHashMap<String, AtomicInteger> consolidationCount = new ConcurrentHashMap<>();

    public MemoryConsolidator(MemoryManager memoryManager,
                               ApplicationEventPublisher eventPublisher,
                               @Value("${app.memory.consolidation.reflection-threshold:3}") int reflectionThreshold) {
        this.memoryManager = memoryManager;
        this.eventPublisher = eventPublisher;
        this.reflectionThreshold = reflectionThreshold;
    }

    @EventListener
    @Async
    public void onFactsExtracted(FactsExtractedEvent event) {
        int factPersisted = event.facts().size();
        for (String fact : event.facts()) {
            try {
                memoryManager.addWithDedup(event.userId(), event.sessionId(), fact, MemoryType.SEMANTIC, 0.6);
            } catch (Exception e) {
                log.warn("[MemoryConsolidator] Failed to persist fact for session={}: {}", event.sessionId(), e.getMessage());
                factPersisted -= 1;
            }
        }
        log.info("[MemoryConsolidator] Persisted {} semantic facts for session={}", factPersisted, event.sessionId());
    }

    @EventListener
    @Async
    public void onEpisodicMemory(EpisodicMemoryEvent event) {
        try {
            memoryManager.add(event.userId(), event.sessionId(), event.summary(), MemoryType.EPISODIC, event.importance());
            log.info("[MemoryConsolidator] Persisted episodic summary for session={}, importance={}", event.sessionId(), event.importance());
        } catch (Exception e) {
            log.warn("[MemoryConsolidator] Failed to persist episodic memory for session={}: {}", event.sessionId(), e.getMessage());
            return;
        }

        triggerReflectionIfNeeded(event.sessionId(), event.userId());
    }

    private void triggerReflectionIfNeeded(String sessionId, String userId) {
        AtomicInteger counter = consolidationCount.computeIfAbsent(sessionId, k -> new AtomicInteger());
        int count = counter.incrementAndGet();
        if (count >= reflectionThreshold && counter.compareAndSet(count, 0)) {
            eventPublisher.publishEvent(new ReflectionRequestEvent(sessionId, userId));
        }
    }
}
