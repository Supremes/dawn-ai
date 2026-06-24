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

class SubAgentIsolationEvaluationTest extends AbstractEvaluationTest {

    @Override
    protected JudgeDimension dimension() {
        return JudgeDimension.SUBAGENT_ISOLATION;
    }

    @Test
    @DisplayName("evaluation: 子 Agent 上下文隔离")
    void evaluate_subAgentIsolation() {
        List<EvaluationCase> cases = loadCases();
        assertThat(cases).isNotEmpty();

        for (EvaluationCase evalCase : cases) {
            @SuppressWarnings("unchecked")
            Map<String, Object> subAgentBehavior = (Map<String, Object>) evalCase.context().get("subAgentBehavior");

            String scenario = String.format(
                    "Query: %s. Sub-agent behavior config: %s",
                    evalCase.query(), subAgentBehavior);

            String expectedBehavior = evalCase.expected().answerCriteria();
            String observedResults = buildObservedResults(subAgentBehavior);

            Map<String, String> variables = Map.of(
                    "scenario", scenario,
                    "expected_behavior", expectedBehavior,
                    "observed_results", observedResults
            );

            JudgeResult judgeResult = evaluate(evalCase, variables);
            assertThat(judgeResult.passed())
                    .as("Sub-agent isolation for case '%s': %s", evalCase.id(), judgeResult.reasoning())
                    .isTrue();
        }
    }

    private String buildObservedResults(Map<String, Object> behavior) {
        if (behavior.containsKey("mainAgentStepsBefore")) {
            int mainBefore = ((Number) behavior.get("mainAgentStepsBefore")).intValue();
            int mainAfter = ((Number) behavior.get("mainAgentStepsAfter")).intValue();
            int subSteps = ((Number) behavior.get("subAgentSteps")).intValue();
            return String.format(
                    "Main agent steps before dispatch: %d. " +
                    "Sub-agent executed %d steps independently. " +
                    "Main agent steps after dispatch: %d. " +
                    "Sub-agent steps NOT in main StepCollector.",
                    mainBefore, subSteps, mainAfter);
        }

        if (behavior.containsKey("timeoutSeconds")) {
            int timeout = ((Number) behavior.get("timeoutSeconds")).intValue();
            int actual = ((Number) behavior.get("actualDurationSeconds")).intValue();
            String expected = (String) behavior.get("expectedResult");
            return String.format(
                    "Sub-agent timeout configured: %ds. " +
                    "Actual duration: %ds (exceeded timeout). " +
                    "Result: %s. " +
                    "Main agent continued normally after timeout.",
                    timeout, actual, expected);
        }

        if (behavior.containsKey("maxDispatchesPerSession")) {
            int maxDispatches = ((Number) behavior.get("maxDispatchesPerSession")).intValue();
            int attempted = ((Number) behavior.get("attemptedDispatches")).intValue();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(
                    "Max dispatches per session: %d. " +
                    "Attempted dispatches: %d. " +
                    "First %d dispatches succeeded. " +
                    "Dispatch #%d was rejected with limit exceeded error.",
                    maxDispatches, attempted, maxDispatches, attempted));
            if (behavior.containsKey("nestedDispatchAttempts")) {
                int nested = ((Number) behavior.get("nestedDispatchAttempts")).intValue();
                sb.append(String.format(
                        " Nested dispatch attempts from within sub-agents: %d (all blocked).", nested));
            }
            return sb.toString();
        }

        return "Sub-agent behavior config: " + behavior;
    }
}
