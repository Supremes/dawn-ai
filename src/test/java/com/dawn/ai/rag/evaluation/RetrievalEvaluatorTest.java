package com.dawn.ai.rag.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dawn.ai.rag.retrieval.RetrievalRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalEvaluatorTest {

    private final RetrievalEvaluator evaluator = new RetrievalEvaluator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("evaluate: hybrid 在样例数据集上应优于 dense")
    void evaluate_hybridOutperformsDenseOnSampleDataset() throws Exception {
        List<RetrievalEvaluationCase> cases = loadCases();

        Function<RetrievalRequest, List<Document>> denseRetriever = request -> denseResults().get(request.getQuery());
        Function<RetrievalRequest, List<Document>> hybridRetriever = request -> hybridResults().get(request.getQuery());

        RetrievalEvaluationReport denseReport = evaluator.evaluate(cases, denseRetriever, 2);
        RetrievalEvaluationReport hybridReport = evaluator.evaluate(cases, hybridRetriever, 2);

        assertThat(denseReport.caseCount()).isEqualTo(2);
        assertThat(hybridReport.recallAtK()).isGreaterThan(denseReport.recallAtK());
        assertThat(hybridReport.mrrAtK()).isGreaterThan(denseReport.mrrAtK());
        assertThat(hybridReport.ndcgAtK()).isGreaterThan(denseReport.ndcgAtK());
    }

    private List<RetrievalEvaluationCase> loadCases() throws Exception {
        try (InputStream inputStream = getClass().getResourceAsStream("/evaluation/retrieval-eval-dataset.json")) {
            return objectMapper.readValue(inputStream, new TypeReference<>() {});
        }
    }

    private Map<String, List<Document>> denseResults() {
        return Map.of(
                "refund policy", List.of(
                        new Document("doc-1", "pricing overview", Map.of()),
                        new Document("doc-2", "refund policy details", Map.of())
                ),
                "invoice settings", List.of(
                        new Document("doc-5", "dashboard summary", Map.of()),
                        new Document("doc-4", "invoice settings guide", Map.of())
                )
        );
    }

    private Map<String, List<Document>> hybridResults() {
        return Map.of(
                "refund policy", List.of(
                        new Document("doc-2", "refund policy details", Map.of()),
                        new Document("doc-3", "refund invoice steps", Map.of())
                ),
                "invoice settings", List.of(
                        new Document("doc-4", "invoice settings guide", Map.of())
                )
        );
    }
}
