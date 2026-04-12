package com.dawn.ai.service;

import java.util.List;

public record RetrievalEvaluationCase(String query, List<String> expectedDocIds) {
}
