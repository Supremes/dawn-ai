package com.dawn.ai.evaluation.dimensions;

import com.dawn.ai.evaluation.base.AbstractEvaluationTest;
import com.dawn.ai.evaluation.base.EvaluationCase;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import com.dawn.ai.service.MemoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MultiTurnCoherenceEvaluationTest extends AbstractEvaluationTest {

    @Autowired
    private MemoryService memoryService;

    @Override
    protected JudgeDimension dimension() {
        return JudgeDimension.MULTI_TURN_COHERENCE;
    }

    @Test
    @DisplayName("evaluation: 多轮对话连贯性")
    void evaluate_multiTurnCoherence() {
        List<EvaluationCase> cases = loadCases();
        assertThat(cases).isNotEmpty();

        for (EvaluationCase evalCase : cases) {
            sleepBetweenCases();
            String sessionId = "eval-multiturn-" + evalCase.id();

            // 预填充记忆：将 memorySnapshot 写入 Redis
            List<Map<String, String>> snapshot = (List<Map<String, String>>) evalCase.context().get("memorySnapshot");
            if (snapshot != null) {
                for (Map<String, String> msg : snapshot) {
                    memoryService.addMessage(sessionId, evalCase.id(), msg.get("role"), msg.get("content"));
                }
            }

            // 发送当前查询
            StreamedAgentResult result = streamAgent(sessionId, evalCase.query());

            String memorySnapshotStr = snapshot != null
                    ? snapshot.stream()
                        .map(m -> m.get("role") + ": " + m.get("content"))
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("(empty)")
                    : "(no memory)";

            Map<String, String> variables = Map.of(
                    "query", evalCase.query(),
                    "memory_snapshot", memorySnapshotStr,
                    "answer", result.finalAnswer() != null ? result.finalAnswer() : "(no answer)",
                    "answer_criteria", evalCase.expected().answerCriteria()
            );

            JudgeResult judgeResult = evaluate(evalCase, variables);
            assertThat(judgeResult.score())
                    .as("Multi-turn coherence for case '%s': %s", evalCase.id(), judgeResult.reasoning())
                    .isGreaterThanOrEqualTo(3.0);
        }
    }
}
