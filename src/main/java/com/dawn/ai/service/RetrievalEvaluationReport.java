package com.dawn.ai.service;

public record RetrievalEvaluationReport(
        int caseCount,
        double recallAtK,
        double mrrAtK,
        double ndcgAtK
) {
}
