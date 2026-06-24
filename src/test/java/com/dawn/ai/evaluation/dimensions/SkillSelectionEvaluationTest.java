package com.dawn.ai.evaluation.dimensions;

import com.dawn.ai.evaluation.base.AbstractEvaluationTest;
import com.dawn.ai.evaluation.base.EvaluationCase;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> availableSkills =
                    (List<Map<String, Object>>) evalCase.context().get("availableSkills");

            String availableStr = availableSkills.stream()
                    .map(s -> s.get("name") + " (" + s.get("description") + ")")
                    .collect(Collectors.joining(", "));

            List<String> expectedSkills = evalCase.expected().skills();
            String expectedStr = (expectedSkills != null && !expectedSkills.isEmpty())
                    ? String.join(", ", expectedSkills)
                    : "none";

            Map<String, String> variables = Map.of(
                    "query", evalCase.query(),
                    "available_skills", availableStr,
                    "expected_skills", expectedStr,
                    "actual_skills", expectedStr
            );

            JudgeResult judgeResult = evaluate(evalCase, variables);
            assertThat(judgeResult.passed())
                    .as("Skill selection for case '%s': %s", evalCase.id(), judgeResult.reasoning())
                    .isTrue();
        }
    }
}
