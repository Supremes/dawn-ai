package com.dawn.ai.rag.retrieval;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@Builder(toBuilder = true)
public class RetrievalRequest {

    private final String query;

    @Builder.Default
    private final int topK = 5;

    @Builder.Default
    private final Map<String, List<String>> metadataFilters = Map.of();

    @Builder.Default
    private final boolean rerankEnabled = true;

    @Builder.Default
    private final RetrievalStrategy strategy = RetrievalStrategy.AUTO;

    public boolean hasMetadataFilters() {
        return metadataFilters != null && !metadataFilters.isEmpty();
    }
}
