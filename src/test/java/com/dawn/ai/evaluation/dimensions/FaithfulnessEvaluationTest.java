package com.dawn.ai.evaluation.dimensions;

import com.dawn.ai.evaluation.base.AbstractEvaluationTest;
import com.dawn.ai.evaluation.base.EvaluationCase;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FaithfulnessEvaluationTest extends AbstractEvaluationTest {

    @Autowired
    private VectorStore vectorStore;

    @Override
    protected JudgeDimension dimension() {
        return JudgeDimension.FAITHFULNESS;
    }

    @Test
    @DisplayName("evaluation: Agent 回答忠实度")
    void evaluate_faithfulness() {
        List<EvaluationCase> cases = loadCases();
        assertThat(cases).isNotEmpty();

        for (EvaluationCase evalCase : cases) {
            sleepBetweenCases();
            String sessionId = "eval-faithfulness-" + evalCase.id();

            indexTestDocuments(evalCase);

            StreamedAgentResult result = streamAgent(sessionId, evalCase.query());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ragDocs = (List<Map<String, Object>>) evalCase.context().get("ragDocuments");
            String retrievedDocuments = ragDocs != null
                    ? ragDocs.stream()
                        .map(doc -> doc.get("content").toString())
                        .collect(Collectors.joining("\n---\n"))
                    : "none";

            Map<String, String> variables = Map.of(
                    "query", evalCase.query(),
                    "answer", result.finalAnswer() != null ? result.finalAnswer() : "",
                    "retrieved_documents", retrievedDocuments
            );

            JudgeResult judgeResult = evaluate(evalCase, variables);
            assertThat(judgeResult.score())
                    .as("Faithfulness for case '%s': %s", evalCase.id(), judgeResult.reasoning())
                    .isGreaterThanOrEqualTo(3.0);
        }
    }

    @SuppressWarnings("unchecked")
    private void indexTestDocuments(EvaluationCase evalCase) {
        List<Map<String, Object>> ragDocs = (List<Map<String, Object>>) evalCase.context().get("ragDocuments");
        if (ragDocs == null || ragDocs.isEmpty()) return;

        try {
            List<Document> documents = ragDocs.stream()
                    .map(doc -> {
                        String docId = (String) doc.get("id");
                        String content = (String) doc.get("content");
                        String uuid = UUID.randomUUID().toString();
                        return new Document(uuid, content, Map.of(
                                "source", "evaluation-test",
                                "evalCaseId", evalCase.id(),
                                "originalId", docId
                        ));
                    })
                    .toList();

            vectorStore.add(documents);
        } catch (Exception e) {
            System.err.println("[Evaluation] Warning: failed to index test documents for " + evalCase.id() + ": " + e.getMessage());
        }
    }
}
