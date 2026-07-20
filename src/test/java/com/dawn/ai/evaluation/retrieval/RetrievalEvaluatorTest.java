package com.dawn.ai.evaluation.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dawn.ai.rag.retrieval.RetrievalRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalEvaluatorTest {

    private final RetrievalEvaluator evaluator = new RetrievalEvaluator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("evaluate: 正序排序的 retriever 各项指标应优于反序排序")
    void evaluate_goodRetrieverOutperformsBadRetriever() throws Exception {
        List<RetrievalEvaluationCase> cases = loadCases();

        RetrievalEvaluationReport goodReport = evaluator.evaluate(cases, request -> {
            RetrievalEvaluationCase c = findCase(cases, request.getQuery());
            return toDocuments(c.mockRankedDocIds());
        }, 3);

        RetrievalEvaluationReport badReport = evaluator.evaluate(cases, request -> {
            RetrievalEvaluationCase c = findCase(cases, request.getQuery());
            List<String> reversed = new ArrayList<>(c.mockRankedDocIds());
            Collections.reverse(reversed);
            return toDocuments(reversed);
        }, 3);

        assertThat(goodReport.recallAtK()).isGreaterThanOrEqualTo(badReport.recallAtK());
        assertThat(goodReport.mrrAtK()).isGreaterThan(badReport.mrrAtK());
        assertThat(goodReport.ndcgAtK()).isGreaterThan(badReport.ndcgAtK());
    }

    @Test
    @DisplayName("evaluate: 本地数据集应输出合理的扩展 RAG 指标")
    void evaluate_localDatasetReportsExtendedMetrics() throws Exception {
        List<RetrievalEvaluationCase> cases = loadCases();

        RetrievalEvaluationReport report = evaluator.evaluate(cases, request -> {
            RetrievalEvaluationCase c = findCase(cases, request.getQuery());
            return toDocuments(c.mockRankedDocIds());
        }, 3);

        assertThat(report.caseCount()).isEqualTo(cases.size());
        assertThat(report.recallAtK()).isGreaterThanOrEqualTo(0.70);
        assertThat(report.hitRateAtK()).isGreaterThanOrEqualTo(0.70);
        assertThat(report.mrrAtK()).isGreaterThanOrEqualTo(0.50);
        assertThat(report.ndcgAtK()).isGreaterThanOrEqualTo(0.50);
        assertThat(report.precisionAtK()).isGreaterThan(0.0);
        assertThat(report.noiseRateAtK()).isLessThan(1.0);
    }

    private List<RetrievalEvaluationCase> loadCases() throws Exception {
        try (InputStream inputStream = getClass().getResourceAsStream("/evaluation/retrieval-eval-dataset.json")) {
            return objectMapper.readValue(inputStream, new TypeReference<>() {});
        }
    }

    private static RetrievalEvaluationCase findCase(List<RetrievalEvaluationCase> cases, String query) {
        return cases.stream()
                .filter(c -> c.query().equals(query))
                .findFirst()
                .orElseThrow();
    }

    private static List<Document> toDocuments(List<String> docIds) {
        return docIds.stream()
                .map(id -> new Document(id, "mock document " + id, Map.of()))
                .toList();
    }
}
