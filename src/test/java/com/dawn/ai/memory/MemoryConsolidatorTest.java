package com.dawn.ai.memory;

import com.dawn.ai.memory.event.EpisodicMemoryEvent;
import com.dawn.ai.memory.event.FactsExtractedEvent;
import com.dawn.ai.memory.event.ReflectionRequestEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryConsolidatorTest {

    private MemoryManager memoryManager;
    private ApplicationEventPublisher eventPublisher;
    private MemoryConsolidator consolidator;

    @BeforeEach
    void setUp() {
        memoryManager = mock(MemoryManager.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        consolidator = new MemoryConsolidator(memoryManager, eventPublisher, 3);
    }

    @Test
    void onFactsExtracted_persistsEachFactAsSemanticMemory() {
        FactsExtractedEvent event = new FactsExtractedEvent(
                "s1", "u1", List.of("User prefers Python.", "User lives in Berlin."));

        consolidator.onFactsExtracted(event);

        verify(memoryManager).addWithDedup("u1", "s1", "User prefers Python.", MemoryType.SEMANTIC, 0.6);
        verify(memoryManager).addWithDedup("u1", "s1", "User lives in Berlin.", MemoryType.SEMANTIC, 0.6);
    }

    @Test
    void onEpisodicMemory_persistsEpisodicSummary() {
        EpisodicMemoryEvent event = new EpisodicMemoryEvent("s1", "u1", "Conversation summary", 0.5);

        consolidator.onEpisodicMemory(event);

        verify(memoryManager).add("u1", "s1", "Conversation summary", MemoryType.EPISODIC, 0.5);
    }

    @Test
    void onEpisodicMemory_publishesReflectionEventWhenThresholdReached() {
        consolidator = new MemoryConsolidator(memoryManager, eventPublisher, 2);

        consolidator.onEpisodicMemory(new EpisodicMemoryEvent("s1", "u1", "Summary A", 0.5));
        consolidator.onEpisodicMemory(new EpisodicMemoryEvent("s1", "u1", "Summary B", 0.5));

        verify(eventPublisher).publishEvent(any(ReflectionRequestEvent.class));
    }

    @Test
    void onEpisodicMemory_doesNotPublishReflectionWhenPersistFails() {
        when(memoryManager.add(anyString(), anyString(), anyString(), any(MemoryType.class), anyDouble()))
                .thenThrow(new RuntimeException("DB down"));

        // Should not throw, and should NOT publish reflection event (early return on failure)
        consolidator.onEpisodicMemory(new EpisodicMemoryEvent("s1", "u1", "Some summary", 0.5));

        verify(eventPublisher, never()).publishEvent(any());
    }
}
