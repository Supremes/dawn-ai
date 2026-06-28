package com.dawn.ai.evaluation.dimensions;

import com.dawn.ai.evaluation.base.AbstractEvaluationTest;
import com.dawn.ai.evaluation.base.EvaluationCase;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.rag.RagService;
import com.dawn.ai.rag.retrieval.RetrievalRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagRecallEvaluationTest extends AbstractEvaluationTest {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RagService ragService;

    @Override
    protected JudgeDimension dimension() {
        return JudgeDimension.RAG_RECALL;
    }

    @Test
    @DisplayName("evaluation: RAG 召回相关性")
    void evaluate_ragRecall() {
        List<EvaluationCase> cases = loadCases();
        assertThat(cases).isNotEmpty();

        List<String> failures = new ArrayList<>();
        for (EvaluationCase evalCase : cases) {
            sleepBetweenCases();

            List<String> indexedDocumentIds = indexEvaluationDocuments(vectorStore, evalCase);
            try {
                RetrievalRequest request = RetrievalRequest.builder()
                        .query(evalCase.query())
                        .metadataFilters(evaluationMetadataFilters(evalCase))
                        .topK(5)
                        .rerankEnabled(false)
                        .build();
                List<Document> retrieved = ragService.retrieve(request);

                List<String> retrievedDocIds = retrieved.stream()
                        .map(this::originalDocumentId)
                        .distinct()
                        .toList();

                assertRecall(evalCase, retrievedDocIds);
            } catch (AssertionError | RuntimeException e) {
                failures.add("%s: %s".formatted(evalCase.id(), e.getMessage()));
            } finally {
                deleteEvaluationDocuments(vectorStore, indexedDocumentIds);
            }
        }

        assertThat(failures)
                .as("RAG recall failures:%n%s", String.join(System.lineSeparator(), failures))
                .isEmpty();
    }

    private void assertRecall(EvaluationCase evalCase, List<String> retrievedDocIds) {
        List<String> expectedDocIds = expectedDocIds(evalCase);
        if (expectedDocIds.isEmpty()) {
            List<String> evalCaseDocIds = evalCaseDocIds(evalCase);
            assertThat(retrievedDocIds)
                    .as("RAG recall@5 for case '%s': expected no docs from current case, retrieved=%s, currentCaseDocs=%s",
                            evalCase.id(), retrievedDocIds, evalCaseDocIds)
                    .doesNotContainAnyElementsOf(evalCaseDocIds);
            return;
        }

        long matchedCount = expectedDocIds.stream()
                .filter(retrievedDocIds::contains)
                .count();
        double recallAt5 = matchedCount / (double) expectedDocIds.size();

        assertThat(retrievedDocIds)
                .as("RAG recall@5 for case '%s': expected=%s, retrieved=%s, recall@5=%.2f",
                        evalCase.id(), expectedDocIds, retrievedDocIds, recallAt5)
                .containsAll(expectedDocIds);
    }

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

    private List<String> evalCaseDocIds(EvaluationCase evalCase) {
        if (evalCase.context() == null) {
            return List.of();
        }

        Object ragDocuments = evalCase.context().get("ragDocuments");
        if (!(ragDocuments instanceof List<?> documents)) {
            return List.of();
        }

        return documents.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(doc -> doc.get("id"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }
}
