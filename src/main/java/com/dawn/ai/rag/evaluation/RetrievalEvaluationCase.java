package com.dawn.ai.rag.evaluation;

import java.util.List;

public record RetrievalEvaluationCase(String query, List<String> expectedDocIds) {
}
