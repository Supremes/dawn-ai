package com.dawn.ai.rag.retrieval;

import com.dawn.ai.rag.retrieval.rerank.CrossEncoderRetrievalReranker;
import com.dawn.ai.rag.retrieval.rerank.HeuristicRetrievalReranker;
import com.dawn.ai.rag.retrieval.rerank.RoutingRetrievalReranker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutingRetrievalRerankerTest {

    @Mock private HeuristicRetrievalReranker heuristicRetrievalReranker;
    @Mock private CrossEncoderRetrievalReranker crossEncoderRetrievalReranker;

    private RoutingRetrievalReranker routingRetrievalReranker;

    @BeforeEach
    void setUp() {
        routingRetrievalReranker = new RoutingRetrievalReranker(heuristicRetrievalReranker, crossEncoderRetrievalReranker);
    }

    @Test
    @DisplayName("rerank: type 为 cross-encoder 且已配置时应委托给 cross-encoder")
    void rerank_delegatesToCrossEncoderWhenConfigured() {
        List<Document> candidates = List.of(new Document("a"), new Document("b"));
        List<Document> expected = List.of(new Document("b"), new Document("a"));
        ReflectionTestUtils.setField(routingRetrievalReranker, "configuredType", "cross-encoder");
        when(crossEncoderRetrievalReranker.isConfigured()).thenReturn(true);
        when(crossEncoderRetrievalReranker.rerank(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(candidates)))
                .thenReturn(expected);

        List<Document> reranked = routingRetrievalReranker.rerank(
                RetrievalRequest.builder().query("refund policy").topK(2).build(),
                candidates);

        assertThat(reranked).isEqualTo(expected);
        verify(crossEncoderRetrievalReranker).rerank(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(candidates));
    }

    @Test
    @DisplayName("rerank: type 为 cross-encoder 但未配置时应回退到 heuristic")
    void rerank_fallsBackToHeuristicWhenCrossEncoderUnavailable() {
        List<Document> candidates = List.of(new Document("a"), new Document("b"));
        ReflectionTestUtils.setField(routingRetrievalReranker, "configuredType", "cross-encoder");
        when(crossEncoderRetrievalReranker.isConfigured()).thenReturn(false);
        when(heuristicRetrievalReranker.rerank(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(candidates)))
                .thenReturn(candidates);

        List<Document> reranked = routingRetrievalReranker.rerank(
                RetrievalRequest.builder().query("refund policy").topK(2).build(),
                candidates);

        assertThat(reranked).isEqualTo(candidates);
        verify(heuristicRetrievalReranker).rerank(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(candidates));
    }
}