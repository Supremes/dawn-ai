package com.dawn.ai.evaluation.dimensions;

import com.dawn.ai.agent.trace.AgentStep;
import com.dawn.ai.evaluation.base.AbstractEvaluationTest;
import com.dawn.ai.evaluation.base.EvaluationCase;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSelectionEvaluationTest extends AbstractEvaluationTest {

    @Override
    protected JudgeDimension dimension() {
        return JudgeDimension.TOOL_SELECTION;
    }

    @Test
    @DisplayName("evaluation: Agent 工具选择正确性")
    void evaluate_toolSelection() {
        List<EvaluationCase> cases = loadCases();
        assertThat(cases).isNotEmpty();

        for (EvaluationCase evalCase : cases) {
            sleepBetweenCases();
            String sessionId = "eval-tool-" + evalCase.id();

            StreamedAgentResult result = streamAgent(sessionId, evalCase.query());

            List<String> actualTools = result.steps().stream()
                    .filter(step -> step.toolName() != null)
                    .map(AgentStep::toolName)
                    .distinct()
                    .toList();

            @SuppressWarnings("unchecked")
            List<String> availableTools = (List<String>) evalCase.context().get("availableTools");

            Map<String, String> variables = Map.of(
                    "query", evalCase.query(),
                    "available_tools", String.join(", ", availableTools),
                    "expected_tools", String.join(", ", evalCase.expected().tools()),
                    "actual_tools", String.join(", ", actualTools)
            );

            JudgeResult judgeResult = evaluate(evalCase, variables);
            assertThat(judgeResult.passed())
                    .as("Tool selection for case '%s': %s", evalCase.id(), judgeResult.reasoning())
                    .isTrue();
        }
    }
}
