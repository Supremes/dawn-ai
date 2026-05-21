package com.dawn.ai.agent.tools;

import com.dawn.ai.agent.trace.StepCollector;
import com.dawn.ai.rag.RagService;
import com.dawn.ai.rag.retrieval.RetrievalRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchToolTopicTest {

    @Mock private RagService ragService;

    private KnowledgeSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new KnowledgeSearchTool(ragService, new SimpleMeterRegistry());
        tool.setDefaultTopK(5);
        tool.initMetrics();
        StepCollector.init(10);
    }

    @Test
    void apply_withTopicId_shouldIncludeTopicIdInMetadataFilter() {
        // Return a non-empty list so the fallback (no-filter retry) is not triggered
        when(ragService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of(new org.springframework.ai.document.Document("result")));
        ArgumentCaptor<RetrievalRequest> captor = ArgumentCaptor.forClass(RetrievalRequest.class);

        tool.apply(new KnowledgeSearchTool.Request("what is saga", null, null, null, "distributed-tx"));

        verify(ragService).retrieve(captor.capture());
        assertThat(captor.getValue().getMetadataFilters())
            .containsKey("topicId")
            .extractingByKey("topicId")
            .asList()
            .containsExactly("distributed-tx");
    }

    @Test
    void apply_withNullTopicId_shouldNotAddTopicIdFilter() {
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of());
        ArgumentCaptor<RetrievalRequest> captor = ArgumentCaptor.forClass(RetrievalRequest.class);

        tool.apply(new KnowledgeSearchTool.Request("what is saga", null, null, null, null));

        verify(ragService).retrieve(captor.capture());
        assertThat(captor.getValue().getMetadataFilters()).doesNotContainKey("topicId");
    }
}
