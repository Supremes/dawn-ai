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
        List<RetrievalEvaluationCase> cases = List.of(
                new RetrievalEvaluationCase("refund policy", List.of("doc-2", "doc-3")),
                new RetrievalEvaluationCase("invoice settings", List.of("doc-4"))
        );

        Function<RetrievalRequest, List<Document>> denseRetriever = request -> denseResults().get(request.getQuery());
        Function<RetrievalRequest, List<Document>> hybridRetriever = request -> hybridResults().get(request.getQuery());

        RetrievalEvaluationReport denseReport = evaluator.evaluate(cases, denseRetriever, 2);
        RetrievalEvaluationReport hybridReport = evaluator.evaluate(cases, hybridRetriever, 2);

        assertThat(denseReport.caseCount()).isEqualTo(2);
        assertThat(hybridReport.recallAtK()).isGreaterThan(denseReport.recallAtK());
        assertThat(hybridReport.precisionAtK()).isGreaterThan(denseReport.precisionAtK());
        assertThat(hybridReport.noiseRateAtK()).isLessThan(denseReport.noiseRateAtK());
        assertThat(hybridReport.hitRateAtK()).isGreaterThan(denseReport.hitRateAtK());
        assertThat(hybridReport.mrrAtK()).isGreaterThan(denseReport.mrrAtK());
        assertThat(hybridReport.ndcgAtK()).isGreaterThan(denseReport.ndcgAtK());
    }

    @Test
    @DisplayName("evaluate: 60 条本地数据集应输出扩展 RAG 指标")
    void evaluate_localDatasetReportsExtendedMetrics() throws Exception {
        List<RetrievalEvaluationCase> cases = loadCases();

        RetrievalEvaluationReport report = evaluator.evaluate(
                cases,
                request -> cases.stream()
                        .filter(c -> c.query().equals(request.getQuery()))
                        .findFirst()
                        .orElseThrow()
                        .mockRankedDocIds().stream()
                        .map(id -> new Document(id, "mock document " + id, Map.of()))
                        .toList(),
                3);

        assertThat(report.caseCount()).isEqualTo(60);
        assertThat(report.recallAtK()).isCloseTo(0.9667, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(report.hitRateAtK()).isCloseTo(0.9667, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(report.mrrAtK()).isCloseTo(0.9083, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(report.ndcgAtK()).isGreaterThanOrEqualTo(0.90);
        assertThat(report.precisionAtK()).isCloseTo(58.0 / 180.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(report.noiseRateAtK()).isCloseTo(122.0 / 180.0, org.assertj.core.data.Offset.offset(0.0001));
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
                        new Document("doc-1", "pricing overview", Map.of())
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
