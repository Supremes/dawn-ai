package com.dawn.ai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryConsolidatorTest {

    private VectorStore vectorStore;
    private ApplicationEventPublisher eventPublisher;
    private MemoryConsolidator consolidator;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        consolidator = new MemoryConsolidator(vectorStore, eventPublisher, 3);
    }

    @Test
    void onConsolidationRequest_writesDocumentToVectorStore() {
        SummaryResult summary = new SummaryResult("s1", "User prefers Python.", 0.5, Instant.now());
        consolidator.onConsolidationRequest(new ConsolidationRequestEvent(summary));

        verify(vectorStore).add(argThat(docs ->
                docs.size() == 1 &&
                docs.get(0).getText().equals("User prefers Python.") &&
                "summary".equals(docs.get(0).getMetadata().get("type")) &&
                "s1".equals(docs.get(0).getMetadata().get("sessionId"))
        ));
    }

    @Test
    void onConsolidationRequest_publishesReflectionEventWhenThresholdReached() {
        consolidator = new MemoryConsolidator(vectorStore, eventPublisher, 2);

        SummaryResult s1 = new SummaryResult("s1", "Summary A", 0.5, Instant.now());
        SummaryResult s2 = new SummaryResult("s1", "Summary B", 0.5, Instant.now());

        consolidator.onConsolidationRequest(new ConsolidationRequestEvent(s1));
        consolidator.onConsolidationRequest(new ConsolidationRequestEvent(s2));

        verify(eventPublisher).publishEvent(any(ReflectionRequestEvent.class));
    }

    @Test
    void onConsolidationRequest_stillSucceedsWhenVectorStoreFails() {
        doThrow(new RuntimeException("PGVector down")).when(vectorStore).add(anyList());

        SummaryResult summary = new SummaryResult("s1", "Some summary", 0.5, Instant.now());
        consolidator.onConsolidationRequest(new ConsolidationRequestEvent(summary));

        // Should not throw, and should NOT publish reflection event (vectorStore failed → return early)
        verify(eventPublisher, never()).publishEvent(any());
    }
}
