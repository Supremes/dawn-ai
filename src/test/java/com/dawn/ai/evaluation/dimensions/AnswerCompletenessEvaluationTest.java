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

import static org.assertj.core.api.Assertions.assertThat;

class AnswerCompletenessEvaluationTest extends AbstractEvaluationTest {

    @Autowired
    private VectorStore vectorStore;

    @Override
    protected JudgeDimension dimension() {
        return JudgeDimension.ANSWER_COMPLETENESS;
    }

    @Test
    @DisplayName("evaluation: Answer 完整性")
    void evaluate_answerCompleteness() {
        List<EvaluationCase> cases = loadCases();
        assertThat(cases).isNotEmpty();

        for (EvaluationCase evalCase : cases) {
            sleepBetweenCases();
            String sessionId = "eval-answer-" + evalCase.id();

            // 预索引测试文档到向量库
            indexTestDocuments(evalCase);

            StreamedAgentResult result = streamAgent(sessionId, evalCase.query());

            String answerCriteria = evalCase.expected().answerCriteria();
            Map<String, String> variables = Map.of(
                    "query", evalCase.query(),
                    "answer", result.finalAnswer() != null ? result.finalAnswer() : "(no answer)",
                    "answer_criteria", answerCriteria != null ? answerCriteria : "(no criteria)"
            );

            JudgeResult judgeResult = evaluate(evalCase, variables);
            assertThat(judgeResult.score())
                    .as("Answer completeness for case '%s': %s", evalCase.id(), judgeResult.reasoning())
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
