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

class SkillSelectionEvaluationTest extends AbstractEvaluationTest {

    @Override
    protected JudgeDimension dimension() {
        return JudgeDimension.SKILL_SELECTION;
    }

    @Test
    @DisplayName("evaluation: Agent 技能选择正确性")
    void evaluate_skillSelection() {
        List<EvaluationCase> cases = loadCases();
        assertThat(cases).isNotEmpty();

        for (EvaluationCase evalCase : cases) {
            sleepBetweenCases();
            String sessionId = "eval-skill-" + evalCase.id();

            StreamedAgentResult result = streamAgent(sessionId, evalCase.query());

            List<String> actualSkills = result.steps().stream()
                    .filter(step -> step.toolName() != null)
                    .map(AgentStep::toolName)
                    .distinct()
                    .toList();

            @SuppressWarnings("unchecked")
            List<String> availableSkills = (List<String>) evalCase.context().get("availableSkills");

            Map<String, String> variables = Map.of(
                    "query", evalCase.query(),
                    "available_skills", String.join(", ", availableSkills),
                    "expected_skills", String.join(", ", evalCase.expected().tools()),
                    "actual_skills", String.join(", ", actualSkills)
            );

            JudgeResult judgeResult = evaluate(evalCase, variables);
            assertThat(judgeResult.passed())
                    .as("Skill selection for case '%s': %s", evalCase.id(), judgeResult.reasoning())
                    .isTrue();
        }
    }
}
