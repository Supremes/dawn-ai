package com.dawn.ai.evaluation.dimensions;

import com.dawn.ai.agent.tools.skill.LoadSkillTool;
import com.dawn.ai.agent.tools.skill.ReadSkillResourceTool;
import com.dawn.ai.agent.trace.AgentStep;
import com.dawn.ai.evaluation.base.AbstractEvaluationTest;
import com.dawn.ai.evaluation.base.EvaluationCase;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SkillSelectionEvaluationTest extends AbstractEvaluationTest {

    private static final Pattern RECORD_FIELD_PATTERN = Pattern.compile("\\b(name|skill)=([^,\\]]+)");

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
            String sessionId = "eval-skill-selection-" + evalCase.id() + "-" + UUID.randomUUID().toString().substring(0, 8);

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
            StreamedAgentResult result = streamAgent(sessionId, evalCase.query());
            List<String> actualSkills = extractActualSkills(result.steps());
            String actualStr = actualSkills.isEmpty() ? "none" : String.join(", ", actualSkills);

            Map<String, String> variables = Map.of(
                    "query", evalCase.query(),
                    "available_skills", availableStr,
                    "expected_skills", expectedStr,
                    "actual_skills", actualStr
            );

            JudgeResult judgeResult = evaluate(evalCase, variables);
            assertThat(judgeResult.passed())
                    .as("Skill selection for case '%s': %s", evalCase.id(), judgeResult.reasoning())
                    .isTrue();
        }
    }

    private List<String> extractActualSkills(List<AgentStep> steps) {
        Set<String> actualSkills = new LinkedHashSet<>();
        for (AgentStep step : flattenSteps(steps)) {
            String skill = null;
            if ("LoadSkillTool".equals(step.toolName())) {
                skill = extractSkillName(step.toolInput(), "name");
            } else if ("ReadSkillResourceTool".equals(step.toolName())) {
                skill = extractSkillName(step.toolInput(), "skill");
            }
            if (skill != null && !skill.isBlank()) {
                actualSkills.add(skill.trim());
            }
        }
        return new ArrayList<>(actualSkills);
    }

    private List<AgentStep> flattenSteps(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }

        List<AgentStep> flattened = new ArrayList<>();
        for (AgentStep step : steps) {
            if (step == null) {
                continue;
            }
            flattened.add(step);
            flattened.addAll(flattenSteps(step.subSteps()));
        }
        return flattened;
    }

    private String extractSkillName(Object input, String fieldName) {
        if (input instanceof LoadSkillTool.Request request) {
            return normalizeSkillName(request.name());
        }
        if (input instanceof ReadSkillResourceTool.Request request) {
            return normalizeSkillName(request.skill());
        }
        if (input instanceof Map<?, ?> map) {
            Object value = map.get(fieldName);
            return normalizeSkillName(value);
        }
        if (input == null) {
            return null;
        }

        Matcher matcher = RECORD_FIELD_PATTERN.matcher(input.toString());
        while (matcher.find()) {
            if (fieldName.equals(matcher.group(1))) {
                return normalizeSkillName(matcher.group(2));
            }
        }
        return null;
    }

    private String normalizeSkillName(Object value) {
        if (value == null) {
            return null;
        }

        String skill = value.toString().trim();
        if (skill.isEmpty() || "null".equalsIgnoreCase(skill)) {
            return null;
        }
        return skill;
    }
}
