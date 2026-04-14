package com.dawn.ai.rag.retrieval.rerank;

import com.dawn.ai.rag.retrieval.RetrievalRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class RoutingRetrievalReranker implements RetrievalReranker {

    private final HeuristicRetrievalReranker heuristicRetrievalReranker;
    private final CrossEncoderRetrievalReranker crossEncoderRetrievalReranker;

    @Value("${app.ai.rag.reranker.type:heuristic}")
    private String configuredType;

    @Override
    public List<Document> rerank(RetrievalRequest request, List<Document> candidates) {
        RetrievalRerankerType rerankerType = RetrievalRerankerType.from(configuredType);
        if (rerankerType == RetrievalRerankerType.CROSS_ENCODER) {
            if (crossEncoderRetrievalReranker.isConfigured()) {
                return crossEncoderRetrievalReranker.rerank(request, candidates);
            }
            log.warn("[Reranker] app.ai.rag.reranker.type=cross-encoder but cross-encoder is not configured, fallback to heuristic");
        }
        return heuristicRetrievalReranker.rerank(request, candidates);
    }
}