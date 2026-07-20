package com.dawn.ai.evaluation.retrieval;

public record RetrievalEvaluationReport(
        int caseCount,
        double recallAtK,
        double precisionAtK,
        double noiseRateAtK,
        double hitRateAtK,
        double mrrAtK,
        double ndcgAtK
) {
}
