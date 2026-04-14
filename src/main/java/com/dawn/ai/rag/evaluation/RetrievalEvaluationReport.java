package com.dawn.ai.rag.evaluation;

public record RetrievalEvaluationReport(
        int caseCount,
        double recallAtK,
        double mrrAtK,
        double ndcgAtK
) {
}
