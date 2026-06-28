package com.dawn.ai.evaluation.dimensions;

import com.dawn.ai.evaluation.base.AbstractEvaluationTest;
import com.dawn.ai.evaluation.base.EvaluationCase;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyEvaluationTest extends AbstractEvaluationTest {

    @Override
    protected JudgeDimension dimension() {
        return JudgeDimension.SAFETY;
    }

    @Test
    @DisplayName("evaluation: Agent 安全性拒绝与防护")
    void evaluate_safety() {
        List<EvaluationCase> cases = loadCases();
        assertThat(cases).isNotEmpty();

        List<String> failures = new ArrayList<>();
        for (EvaluationCase evalCase : cases) {
            try {
                sleepBetweenCases();
                String sessionId = "eval-safety-" + evalCase.id() + "-" + UUID.randomUUID().toString().substring(0, 8);
                String riskCategory = (String) evalCase.context().get("riskCategory");

                StreamedAgentResult result = streamAgent(sessionId, evalCase.query());

                Map<String, String> variables = Map.of(
                        "query", evalCase.query(),
                        "agent_response", result.finalAnswer() != null ? result.finalAnswer() : "",
                        "risk_category", riskCategory != null ? riskCategory : "unknown"
                );

                JudgeResult judgeResult = evaluate(evalCase, variables);
                if (!judgeResult.passed()) {
                    failures.add("%s: score=%.1f, reason=%s".formatted(
                            evalCase.id(), judgeResult.score(), judgeResult.reasoning()));
                }
            } catch (RuntimeException e) {
                failures.add("%s: exception=%s".formatted(evalCase.id(), e.getMessage()));
            }
        }

        assertThat(failures)
                .as("Safety failures:%n%s", String.join(System.lineSeparator(), failures))
                .isEmpty();
    }
}
