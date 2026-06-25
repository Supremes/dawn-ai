package com.dawn.ai.evaluation.dimensions;

import com.dawn.ai.agent.trace.AgentStep;
import com.dawn.ai.evaluation.base.AbstractEvaluationTest;
import com.dawn.ai.evaluation.base.EvaluationCase;
import com.dawn.ai.evaluation.base.EvaluationCaseResult;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

        List<String> failures = new ArrayList<>();

        for (EvaluationCase evalCase : cases) {
            String sessionId = "eval-tool-" + evalCase.id() + "-" + UUID.randomUUID().toString().substring(0, 8);
            try {
                sleepBetweenCases();

                StreamedAgentResult result = streamAgent(sessionId, evalCase.query());
                List<String> actualTools = result.steps().stream()
                        .filter(step -> step.toolName() != null)
                        .map(AgentStep::toolName)
                        .distinct()
                        .toList();

                JudgeResult resultByRules = evaluateByRules(evalCase, actualTools);
                recordCaseResult(new EvaluationCaseResult(
                        evalCase,
                        resultByRules,
                        sessionId,
                        expectedTools(evalCase),
                        actualTools,
                        forbiddenTools(evalCase),
                        allowExtraTools(evalCase),
                        result.finalAnswer(),
                        result.plannerSteps(),
                        result.steps()
                ));
                if (!resultByRules.passed()) {
                    failures.add(formatFailure(evalCase, resultByRules));
                }
            } catch (RuntimeException e) {
                JudgeResult errorResult = new JudgeResult(dimension(), 0.0,
                        "Evaluation case failed with exception: " + e.getMessage());
                recordCaseResult(new EvaluationCaseResult(
                        evalCase,
                        errorResult,
                        sessionId,
                        expectedTools(evalCase),
                        List.of(),
                        forbiddenTools(evalCase),
                        allowExtraTools(evalCase),
                        "",
                        List.of(),
                        List.of()
                ));
                failures.add(formatFailure(evalCase, errorResult));
            }
        }

        assertThat(failures)
                .as("Tool selection failures:%n%s", String.join(System.lineSeparator(), failures))
                .isEmpty();
    }

    private JudgeResult evaluateByRules(EvaluationCase evalCase, List<String> actualTools) {
        List<String> expectedTools = expectedTools(evalCase);
        Set<String> expected = normalizeToolNames(expectedTools);
        Set<String> actual = normalizeToolNames(actualTools);
        boolean allowExtraTools = allowExtraTools(evalCase);

        boolean includesAllExpected = actual.containsAll(expected);
        boolean hasUnexpectedTools = !expected.containsAll(actual);
        double score;
        String verdict;

        if (expected.isEmpty()) {
            score = actual.isEmpty() ? 1.0 : 0.0;
            verdict = actual.isEmpty()
                    ? "No tools were expected and no tools were called."
                    : "No tools were expected, but the agent called unexpected tools.";
        } else if (includesAllExpected && (!hasUnexpectedTools || allowExtraTools)) {
            score = 1.0;
            verdict = hasUnexpectedTools
                    ? "All expected tools were called and extra tools are allowed for this case."
                    : "The agent called exactly the expected tools.";
        } else if (includesAllExpected) {
            score = 0.5;
            verdict = "All expected tools were called, but unexpected extra tools were also called.";
        } else if (actual.stream().anyMatch(expected::contains)) {
            score = 0.5;
            verdict = "The agent called some but not all expected tools.";
        } else {
            score = 0.0;
            verdict = "The agent did not call the expected tools.";
        }

        String reasoning = "%s expected=%s actual=%s allowExtraTools=%s".formatted(
                verdict, expectedTools, actualTools, allowExtraTools);
        return new JudgeResult(dimension(), score, reasoning);
    }

    private List<String> expectedTools(EvaluationCase evalCase) {
        return evalCase.expected().tools() == null ? List.of() : evalCase.expected().tools();
    }

    private List<String> forbiddenTools(EvaluationCase evalCase) {
        return evalCase.expected().forbiddenTools() == null ? List.of() : evalCase.expected().forbiddenTools();
    }

    private boolean allowExtraTools(EvaluationCase evalCase) {
        if (evalCase.expected().allowExtraTools() == null)
            return true;
        return evalCase.expected().allowExtraTools();
    }

    private Set<String> normalizeToolNames(List<String> tools) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String tool : tools) {
            if (tool != null && !tool.isBlank()) {
                normalized.add(tool.trim().toLowerCase());
            }
        }
        return normalized;
    }

    private String formatFailure(EvaluationCase evalCase, JudgeResult result) {
        return "- %s: score=%.1f, reason=%s".formatted(
                evalCase.id(), result.score(), result.reasoning());
    }
}
