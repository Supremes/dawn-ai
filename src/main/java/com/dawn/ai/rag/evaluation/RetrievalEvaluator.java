package com.dawn.ai.rag.evaluation;

import com.dawn.ai.rag.retrieval.RetrievalRequest;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class RetrievalEvaluator {

    public RetrievalEvaluationReport evaluate(
            List<RetrievalEvaluationCase> cases,
            Function<RetrievalRequest, List<Document>> retriever,
            int k) {
        double recall = 0.0;
        double mrr = 0.0;
        double ndcg = 0.0;

        for (RetrievalEvaluationCase evaluationCase : cases) {
            List<Document> ranked = retriever.apply(RetrievalRequest.builder()
                    .query(evaluationCase.query())
                    .topK(k)
                    .build());
            List<String> predictedIds = ranked.stream()
                    .limit(k)
                    .map(Document::getId)
                    .toList();
            Set<String> expectedIds = evaluationCase.expectedDocIds().stream().collect(Collectors.toSet());

            recall += recallAtK(predictedIds, expectedIds);
            mrr += reciprocalRank(predictedIds, expectedIds);
            ndcg += ndcgAtK(predictedIds, expectedIds, k);
        }

        int caseCount = cases.size();
        return new RetrievalEvaluationReport(
                caseCount,
                recall / caseCount,
                mrr / caseCount,
                ndcg / caseCount);
    }

    private double recallAtK(List<String> predictedIds, Set<String> expectedIds) {
        long hits = predictedIds.stream().filter(expectedIds::contains).count();
        return expectedIds.isEmpty() ? 0.0 : (double) hits / expectedIds.size();
    }

    private double reciprocalRank(List<String> predictedIds, Set<String> expectedIds) {
        for (int index = 0; index < predictedIds.size(); index++) {
            if (expectedIds.contains(predictedIds.get(index))) {
                return 1.0 / (index + 1);
            }
        }
        return 0.0;
    }

    private double ndcgAtK(List<String> predictedIds, Set<String> expectedIds, int k) {
        double dcg = 0.0;
        for (int index = 0; index < predictedIds.size(); index++) {
            if (expectedIds.contains(predictedIds.get(index))) {
                dcg += 1.0 / log2(index + 2);
            }
        }

        int idealHits = Math.min(expectedIds.size(), k);
        double idcg = 0.0;
        for (int index = 0; index < idealHits; index++) {
            idcg += 1.0 / log2(index + 2);
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private double log2(int value) {
        return Math.log(value) / Math.log(2);
    }
}
