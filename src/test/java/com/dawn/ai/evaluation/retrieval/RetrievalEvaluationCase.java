package com.dawn.ai.evaluation.retrieval;

import java.util.List;
import java.util.Map;

public record RetrievalEvaluationCase(
        String query,
        List<String> expectedDocIds,
        List<String> hardNegativeDocIds,
        Map<String, List<String>> metadataFilters,
        List<String> mockRankedDocIds
) {
    public RetrievalEvaluationCase(String query, List<String> expectedDocIds) {
        this(query, expectedDocIds, List.of(), Map.of(), List.of());
    }

    public RetrievalEvaluationCase {
        expectedDocIds = expectedDocIds == null ? List.of() : List.copyOf(expectedDocIds);
        hardNegativeDocIds = hardNegativeDocIds == null ? List.of() : List.copyOf(hardNegativeDocIds);
        metadataFilters = metadataFilters == null ? Map.of() : Map.copyOf(metadataFilters);
        mockRankedDocIds = mockRankedDocIds == null ? List.of() : List.copyOf(mockRankedDocIds);
    }
}
