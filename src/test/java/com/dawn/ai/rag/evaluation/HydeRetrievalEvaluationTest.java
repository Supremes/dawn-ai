package com.dawn.ai.rag.evaluation;

import com.dawn.ai.rag.query.HydeQueryGenerator;
import com.dawn.ai.rag.retrieval.RetrievalRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Demonstration test: with the same baseline retrieval engine, the HyDE-rewritten
 * query string should be a better retrieval input than the bare keyword query
 * for under-specified / paraphrased queries.
 *
 * The retrieval engine here is mocked: real-world hypothetical documents tend to
 * lexically overlap with relevant chunks, which is exactly what the mock encodes.
 */
class HydeRetrievalEvaluationTest {

    private final RetrievalEvaluator evaluator = new RetrievalEvaluator();

    @Test
    @DisplayName("HyDE 改写后查询 + 同一检索器，metric 应不劣于 baseline")
    void hyde_improvesRetrievalMetricsOnParaphrasedQueries() {
        // Same evaluation cases used by RetrievalEvaluatorTest.
        List<RetrievalEvaluationCase> cases = List.of(
                new RetrievalEvaluationCase("退款政策怎么走", List.of("doc-2", "doc-3")),
                new RetrievalEvaluationCase("发票怎么改", List.of("doc-4"))
        );

        // Baseline retriever: short paraphrased queries miss relevant docs.
        Map<String, List<Document>> baselineCorpus = Map.of(
                "退款政策怎么走", List.of(
                        new Document("doc-1", "pricing overview", Map.of()),
                        new Document("doc-5", "dashboard summary", Map.of())
                ),
                "发票怎么改", List.of(
                        new Document("doc-5", "dashboard summary", Map.of())
                )
        );

        // After HyDE expands to a hypothetical answer, the query string lexically
        // matches the target docs much better.
        Map<String, List<Document>> hydeCorpus = Map.of(
                "退款政策怎么走 → 假设答案：根据退款条款，用户可在 7 天内申请退款...",
                List.of(
                        new Document("doc-2", "refund policy details", Map.of()),
                        new Document("doc-3", "refund invoice steps", Map.of())
                ),
                "发票怎么改 → 假设答案：在仪表盘 invoice settings 页面可修改抬头与税号...",
                List.of(
                        new Document("doc-4", "invoice settings guide", Map.of()),
                        new Document("doc-3", "refund invoice steps", Map.of())
                )
        );

        // Stubbed HyDE generator that maps query -> hypothetical text used as retrieval key.
        HydeQueryGenerator hyde = mock(HydeQueryGenerator.class);
        when(hyde.generate(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            return switch (q) {
                case "退款政策怎么走" -> "退款政策怎么走 → 假设答案：根据退款条款，用户可在 7 天内申请退款...";
                case "发票怎么改" -> "发票怎么改 → 假设答案：在仪表盘 invoice settings 页面可修改抬头与税号...";
                default -> q;
            };
        });

        Function<RetrievalRequest, List<Document>> baselineRetriever =
                req -> baselineCorpus.getOrDefault(req.getQuery(), List.of());

        Function<RetrievalRequest, List<Document>> hydeRetriever = req -> {
            String hypothetical = hyde.generate(req.getQuery());
            return hydeCorpus.getOrDefault(hypothetical, List.of());
        };

        RetrievalEvaluationReport baseline = evaluator.evaluate(cases, baselineRetriever, 2);
        RetrievalEvaluationReport withHyde = evaluator.evaluate(cases, hydeRetriever, 2);

        assertThat(withHyde.recallAtK()).isGreaterThan(baseline.recallAtK());
        assertThat(withHyde.mrrAtK()).isGreaterThan(baseline.mrrAtK());
        assertThat(withHyde.ndcgAtK()).isGreaterThan(baseline.ndcgAtK());
    }
}
