package com.dawn.ai.evaluation.retrieval;

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
 * 演示型测试：检索引擎不变，对于表述不完整 / 换了说法的 query，
 * 经 HyDE 改写后的 query 字符串应该比裸关键词 query 更适合拿去做检索。
 *
 * 这里的检索引擎是 mock 的：现实中假设性文档往往和相关 chunk 在词面上有重叠，
 * mock 编码的就是这一点。
 */
class HydeRetrievalEvaluationTest {

    private final RetrievalEvaluator evaluator = new RetrievalEvaluator();

    @Test
    @DisplayName("HyDE 改写后查询 + 同一检索器，metric 应不劣于 baseline")
    void hyde_improvesRetrievalMetricsOnParaphrasedQueries() {
        // 和 RetrievalEvaluatorTest 用的是同一组评测用例。
        List<RetrievalEvaluationCase> cases = List.of(
                new RetrievalEvaluationCase("退款政策怎么走", List.of("doc-2", "doc-3")),
                new RetrievalEvaluationCase("发票怎么改", List.of("doc-4"))
        );

        // baseline 检索器：简短的换说法 query 会漏掉相关文档。
        Map<String, List<Document>> baselineCorpus = Map.of(
                "退款政策怎么走", List.of(
                        new Document("doc-1", "pricing overview", Map.of()),
                        new Document("doc-5", "dashboard summary", Map.of())
                ),
                "发票怎么改", List.of(
                        new Document("doc-5", "dashboard summary", Map.of())
                )
        );

        // HyDE 把 query 扩展成假设答案之后，query 字符串在词面上
        // 和目标文档的匹配度明显更高。
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

        // stub 掉的 HyDE 生成器：把 query 映射成假设文本，作为检索 key。
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
