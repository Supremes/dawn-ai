package com.dawn.ai.evaluation.dimensions;

import com.dawn.ai.evaluation.base.AbstractEvaluationTest;
import com.dawn.ai.evaluation.base.EvaluationCase;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class GroundednessEvaluationTest extends AbstractEvaluationTest {

    @Autowired
    private VectorStore vectorStore;

    @Override
    protected JudgeDimension dimension() {
        return JudgeDimension.GROUNDEDNESS;
    }

    @Test
    @DisplayName("evaluation: Agent 回答 Groundedness")
    void evaluate_groundedness() {
        List<EvaluationCase> cases = loadCases();
        assertThat(cases).isNotEmpty();

        List<String> failures = new ArrayList<>();
        for (EvaluationCase evalCase : cases) {
            sleepBetweenCases();
            String sessionId = evaluationSessionId("eval-groundedness", evalCase);
            List<String> indexedDocumentIds = indexEvaluationDocuments(vectorStore, evalCase);

            try {
                StreamedAgentResult result = streamAgent(sessionId, evalCase.query(), evaluationTopicId(evalCase));

                Map<String, String> variables = Map.of(
                        "query", evalCase.query(),
                        "answer", result.finalAnswer() != null ? result.finalAnswer() : "",
                        "grounding_documents", groundingDocuments(evalCase)
                );

                JudgeResult judgeResult = evaluate(evalCase, variables);
                if (judgeResult.score() < 3.0) {
                    failures.add("%s: score=%.1f, reason=%s".formatted(
                            evalCase.id(), judgeResult.score(), judgeResult.reasoning()));
                }
            } catch (RuntimeException e) {
                failures.add("%s: exception=%s".formatted(evalCase.id(), e.getMessage()));
            } finally {
                deleteEvaluationDocuments(vectorStore, indexedDocumentIds);
            }
        }

        assertThat(failures)
                .as("Groundedness failures:%n%s", String.join(System.lineSeparator(), failures))
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    private String groundingDocuments(EvaluationCase evalCase) {
        List<Map<String, Object>> ragDocs = (List<Map<String, Object>>) evalCase.context().get("ragDocuments");
        if (ragDocs == null) {
            return "none";
        }
        return ragDocs.stream()
                .map(doc -> doc.get("content").toString())
                .collect(Collectors.joining("\n---\n"));
    }
}
