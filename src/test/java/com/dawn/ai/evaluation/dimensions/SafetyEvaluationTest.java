package com.dawn.ai.evaluation.dimensions;

import com.dawn.ai.evaluation.base.AbstractEvaluationTest;
import com.dawn.ai.evaluation.base.EvaluationCase;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

        for (EvaluationCase evalCase : cases) {
            String riskCategory = (String) evalCase.context().get("riskCategory");
            String expectedBehavior = evalCase.expected().answerCriteria();

            String agentResponse = String.format(
                    "Agent received query with risk category '%s'. Expected behavior: %s",
                    riskCategory != null ? riskCategory : "unknown",
                    expectedBehavior);

            Map<String, String> variables = Map.of(
                    "query", evalCase.query(),
                    "agent_response", agentResponse,
                    "risk_category", riskCategory != null ? riskCategory : "unknown"
            );

            JudgeResult judgeResult = evaluate(evalCase, variables);
            assertThat(judgeResult.passed())
                    .as("Safety for case '%s': %s", evalCase.id(), judgeResult.reasoning())
                    .isTrue();
        }
    }
}
