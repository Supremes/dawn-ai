package com.dawn.ai.evaluation.dimensions;

import com.dawn.ai.evaluation.base.AbstractEvaluationTest;
import com.dawn.ai.evaluation.base.EvaluationCase;
import com.dawn.ai.evaluation.base.EvaluationCaseResult;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import com.dawn.ai.rag.RagService;
import com.dawn.ai.rag.retrieval.RetrievalRequest;
import com.dawn.ai.rag.retrieval.RetrievalRouter;
import com.dawn.ai.rag.retrieval.RetrievalStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RagRecallEvaluationTest extends AbstractEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(RagRecallEvaluationTest.class);
    private static final int K = 5;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RagService ragService;

    @Autowired(required = false)
    private RetrievalRouter retrievalRouter;

    @Override
    protected JudgeDimension dimension() {
        return JudgeDimension.RAG_RECALL;
    }

    @Test
    @DisplayName("evaluation: RAG 召回相关性 (IR metrics + rule-based scoring)")
    void evaluate_ragRecall() {
        List<EvaluationCase> cases = loadCases();
        assertThat(cases).isNotEmpty();

        List<String> failures = new ArrayList<>();

        // Accumulators for aggregate IR metrics
        double sumRecall = 0, sumPrecision = 0, sumHit = 0, sumMrr = 0, sumNdcg = 0, sumNoise = 0;
        int caseCount = 0;

        for (EvaluationCase evalCase : cases) {
            String sessionId = evaluationSessionId("eval-rag", evalCase);
            sleepBetweenCases();

            List<String> indexedDocumentIds = indexEvaluationDocuments(vectorStore, evalCase);
            try {
                // --- Retrieve ---
                RetrievalRequest request = RetrievalRequest.builder()
                        .query(evalCase.query())
                        .metadataFilters(evaluationMetadataFilters(evalCase))
                        .topK(K)
                        .rerankEnabled(false)
                        .build();

                List<Document> retrieved = ragService.retrieve(request);

                List<String> retrievedDocIds = retrieved.stream()
                        .map(this::originalDocumentId)
                        .distinct()
                        .toList();

                List<String> expectedDocIds = expectedDocIds(evalCase);
                List<String> allCaseDocIds = evalCaseDocIds(evalCase);
                boolean isNegativeCase = expectedDocIds.isEmpty();

                // --- Compute 6 IR metrics inline ---
                double recallAtK;
                double precisionAtK;
                double hitAtK;
                double mrrAtK;
                double ndcgAtK;
                double noiseAtK;

                if (isNegativeCase) {
                    // Negative case: no expected docs; metrics measure correct absence
                    boolean noLeakage = retrievedDocIds.stream().noneMatch(allCaseDocIds::contains);
                    recallAtK = noLeakage ? 1.0 : 0.0;
                    precisionAtK = noLeakage ? 1.0 : 0.0;
                    hitAtK = noLeakage ? 1.0 : 0.0;
                    mrrAtK = noLeakage ? 1.0 : 0.0;
                    ndcgAtK = noLeakage ? 1.0 : 0.0;
                    noiseAtK = noLeakage ? 0.0 : 1.0;
                } else {
                    recallAtK = computeRecallAtK(retrievedDocIds, expectedDocIds);
                    precisionAtK = computePrecisionAtK(retrievedDocIds, expectedDocIds);
                    hitAtK = computeHitAtK(retrievedDocIds, expectedDocIds);
                    mrrAtK = computeMrrAtK(retrievedDocIds, expectedDocIds);
                    ndcgAtK = computeNdcgAtK(retrievedDocIds, expectedDocIds);
                    noiseAtK = 1.0 - precisionAtK;
                }

                // --- Retrieval strategy (optional) ---
                String strategy = resolveStrategy(request);

                // --- Rule-based scoring (deterministic, no LLM Judge) ---
                JudgeResult judgeResult = evaluateByRules(evalCase, expectedDocIds,
                        retrievedDocIds, allCaseDocIds, recallAtK, precisionAtK, isNegativeCase);

                // --- Record case result following ToolSelectionEvaluationTest pattern ---
                recordCaseResult(EvaluationCaseResult.forRagRecall(
                        evalCase, judgeResult, sessionId, "",
                        expectedDocIds, retrievedDocIds,
                        recallAtK, precisionAtK, hitAtK, mrrAtK, ndcgAtK,
                        strategy));

                // --- Per-case IR log ---
                log.info("[RagRecallEval] case={} | recall@{}={} | precision@{}={} | hit@{}={} | " +
                                "mrr@{}={} | ndcg@{}={} | noise@{}={} | strategy={} | score={}",
                        evalCase.id(),
                        K, fmt(recallAtK), K, fmt(precisionAtK), K, fmt(hitAtK),
                        K, fmt(mrrAtK), K, fmt(ndcgAtK), K, fmt(noiseAtK),
                        strategy, fmt(judgeResult.score()));

                // Accumulate for aggregate report
                sumRecall += recallAtK;
                sumPrecision += precisionAtK;
                sumHit += hitAtK;
                sumMrr += mrrAtK;
                sumNdcg += ndcgAtK;
                sumNoise += noiseAtK;
                caseCount++;

                if (!judgeResult.passed()) {
                    failures.add(formatFailure(evalCase, judgeResult, recallAtK));
                }
            } catch (RuntimeException e) {
                JudgeResult errorResult = new JudgeResult(dimension(), 0.0,
                        "Exception: " + e.getMessage());
                recordCaseResult(EvaluationCaseResult.forRagRecall(
                        evalCase, errorResult, sessionId, "",
                        expectedDocIds(evalCase), List.of(),
                        0.0, 0.0, 0.0, 0.0, 0.0, ""));
                failures.add(formatFailure(evalCase, errorResult, 0.0));
                caseCount++;
            } finally {
                deleteEvaluationDocuments(vectorStore, indexedDocumentIds);
            }
        }

        // --- Aggregate IR report ---
        printAggregateReport(caseCount, sumRecall, sumPrecision, sumHit, sumMrr, sumNdcg, sumNoise);

        assertThat(failures)
                .as("RAG recall failures:%n%s", String.join(System.lineSeparator(), failures))
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Rule-based evaluation (deterministic, no LLM Judge)
    // -------------------------------------------------------------------------

    private JudgeResult evaluateByRules(EvaluationCase evalCase,
                                        List<String> expectedDocIds,
                                        List<String> retrievedDocIds,
                                        List<String> allCaseDocIds,
                                        double recallAtK,
                                        double precisionAtK,
                                        boolean isNegativeCase) {
        if (isNegativeCase) {
            // Negative case: score 5.0 if NO docs from this case's ragDocuments are retrieved.
            boolean noLeakage = retrievedDocIds.stream().noneMatch(allCaseDocIds::contains);
            double score = noLeakage ? 5.0 : 1.0;
            String reasoning = noLeakage
                    ? "Negative case: no docs from case pool were retrieved (correct)."
                    : "Negative case: docs from case pool leaked into results. retrieved=%s, casePool=%s"
                            .formatted(retrievedDocIds, allCaseDocIds);
            return new JudgeResult(dimension(), score, reasoning);
        }

        Set<String> retrieved = new LinkedHashSet<>(retrievedDocIds);
        List<String> matched = expectedDocIds.stream().filter(retrieved::contains).toList();
        List<String> missed = expectedDocIds.stream().filter(id -> !retrieved.contains(id)).toList();
        List<String> noise = retrievedDocIds.stream().filter(id -> !expectedDocIds.contains(id)).toList();

        double score;
        String verdict;
        if (recallAtK >= 1.0 && noise.isEmpty()) {
            score = 5.0;
            verdict = "All expected docs retrieved with no irrelevant docs.";
        } else if (recallAtK >= 1.0) {
            score = 4.0;
            verdict = "All expected docs retrieved with %d irrelevant doc(s) mixed in.".formatted(noise.size());
        } else if (recallAtK >= 0.7) {
            score = 3.0;
            verdict = "Most expected docs retrieved: %d/%d.".formatted(matched.size(), expectedDocIds.size());
        } else if (recallAtK > 0.0) {
            score = 2.0;
            verdict = "Few expected docs retrieved: %d/%d.".formatted(matched.size(), expectedDocIds.size());
        } else {
            score = 1.0;
            verdict = "No expected docs retrieved.";
        }

        String reasoning = "%s recall@%d=%.4f precision@%d=%.4f expected=%s retrieved=%s matched=%s missed=%s noise=%s".formatted(
                verdict, K, recallAtK, K, precisionAtK, expectedDocIds, retrievedDocIds, matched, missed, noise);
        return new JudgeResult(dimension(), score, reasoning);
    }

    // -------------------------------------------------------------------------
    // IR metric computation (inlined, no external dependency)
    // -------------------------------------------------------------------------

    /** Recall@K = matched expected docs / total expected docs */
    private double computeRecallAtK(List<String> retrievedDocIds, List<String> expectedDocIds) {
        if (expectedDocIds.isEmpty()) return 0.0;
        long hits = expectedDocIds.stream().filter(retrievedDocIds::contains).count();
        return (double) hits / expectedDocIds.size();
    }

    /** Precision@K = matched expected docs / K */
    private double computePrecisionAtK(List<String> retrievedDocIds, List<String> expectedDocIds) {
        if (expectedDocIds.isEmpty()) return 0.0;
        Set<String> expected = new LinkedHashSet<>(expectedDocIds);
        long hits = retrievedDocIds.stream().filter(expected::contains).count();
        return (double) hits / K;
    }

    /** HitRate@K = at least one expected doc found ? 1.0 : 0.0 */
    private double computeHitAtK(List<String> retrievedDocIds, List<String> expectedDocIds) {
        if (expectedDocIds.isEmpty()) return 0.0;
        Set<String> expected = new LinkedHashSet<>(expectedDocIds);
        return retrievedDocIds.stream().anyMatch(expected::contains) ? 1.0 : 0.0;
    }

    /** MRR@K = 1.0 / (rank of first expected doc in retrieved list) */
    private double computeMrrAtK(List<String> retrievedDocIds, List<String> expectedDocIds) {
        if (expectedDocIds.isEmpty()) return 0.0;
        Set<String> expected = new LinkedHashSet<>(expectedDocIds);
        for (int i = 0; i < retrievedDocIds.size(); i++) {
            if (expected.contains(retrievedDocIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * NDCG@K = DCG@K / IDCG@K.
     * Relevance is binary: 1.0 if doc is in expectedDocIds, 0.0 otherwise.
     */
    private double computeNdcgAtK(List<String> retrievedDocIds, List<String> expectedDocIds) {
        if (expectedDocIds.isEmpty()) return 0.0;
        Set<String> expected = new LinkedHashSet<>(expectedDocIds);

        // DCG@K = sum( rel_i / log2(i+2) ) for i in [0, min(K, retrieved.size()))
        double dcg = 0.0;
        int limit = Math.min(K, retrievedDocIds.size());
        for (int i = 0; i < limit; i++) {
            if (expected.contains(retrievedDocIds.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }

        // IDCG@K: ideal ranking places all relevant docs at the top
        int idealHits = Math.min(expected.size(), K);
        double idcg = 0.0;
        for (int i = 0; i < idealHits; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }

        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    // -------------------------------------------------------------------------
    // Aggregate report
    // -------------------------------------------------------------------------

    private void printAggregateReport(int caseCount,
                                      double sumRecall, double sumPrecision, double sumHit,
                                      double sumMrr, double sumNdcg, double sumNoise) {
        if (caseCount == 0) return;

        log.info("\n" +
                "=================================================================\n" +
                "       RAG RECALL - AGGREGATE IR METRICS REPORT\n" +
                "=================================================================\n" +
                "  Total cases evaluated : {}\n" +
                "  Avg Recall@{}          : {}\n" +
                "  Avg Precision@{}       : {}\n" +
                "  Avg HitRate@{}         : {}\n" +
                "  Avg MRR@{}             : {}\n" +
                "  Avg NDCG@{}            : {}\n" +
                "  Avg NoiseRate@{}       : {}\n" +
                "=================================================================",
                caseCount,
                K, fmt(sumRecall / caseCount),
                K, fmt(sumPrecision / caseCount),
                K, fmt(sumHit / caseCount),
                K, fmt(sumMrr / caseCount),
                K, fmt(sumNdcg / caseCount),
                K, fmt(sumNoise / caseCount));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<String> expectedDocIds(EvaluationCase evalCase) {
        if (evalCase.expected() == null || evalCase.expected().docIds() == null) {
            return List.of();
        }
        return evalCase.expected().docIds();
    }

    private String originalDocumentId(Document document) {
        Object originalId = document.getMetadata().get("originalId");
        return originalId != null ? originalId.toString() : document.getId();
    }

    @SuppressWarnings("unchecked")
    private List<String> evalCaseDocIds(EvaluationCase evalCase) {
        if (evalCase.context() == null) return List.of();
        Object ragDocuments = evalCase.context().get("ragDocuments");
        if (!(ragDocuments instanceof List<?> documents)) return List.of();
        return documents.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(doc -> doc.get("id"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private String resolveStrategy(RetrievalRequest request) {
        if (retrievalRouter == null) return "";
        try {
            RetrievalStrategy strategy = retrievalRouter.route(request);
            return strategy.name();
        } catch (Exception e) {
            log.warn("[RagRecallEval] router.route() failed: {}", e.getMessage());
            return "";
        }
    }

    private String formatFailure(EvaluationCase evalCase, JudgeResult result, double recall) {
        return "- %s: score=%.1f, recall@%d=%.4f, reason=%s".formatted(
                evalCase.id(), result.score(), K, recall, result.reasoning());
    }

    private static String fmt(double value) {
        return String.format("%.4f", value);
    }
}
