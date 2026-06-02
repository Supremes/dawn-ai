package com.dawn.ai.evaluation.dimensions;

import com.dawn.ai.agent.orchestration.AgentOrchestrator;
import com.dawn.ai.agent.planning.PlanStep;
import com.dawn.ai.evaluation.base.AbstractEvaluationTest;
import com.dawn.ai.evaluation.base.EvaluationCase;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptAssemblyEvaluationTest extends AbstractEvaluationTest {

    @Autowired
    private AgentOrchestrator agentOrchestrator;

    @Override
    protected JudgeDimension dimension() {
        return JudgeDimension.PROMPT_ASSEMBLY;
    }

    @Test
    @DisplayName("evaluation: Prompt 拼装正确性")
    void evaluate_promptAssembly() throws Exception {
        List<EvaluationCase> cases = loadCases();
        assertThat(cases).isNotEmpty();

        // buildSystemPrompt 是 private，通过反射调用
        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "buildSystemPrompt", List.class, String.class, String.class);
        method.setAccessible(true);

        for (EvaluationCase evalCase : cases) {
            @SuppressWarnings("unchecked")
            Map<String, String> promptSegments = (Map<String, String>) evalCase.context().get("promptSegments");

            // prompt_assembly_002 需要带执行计划
            List<PlanStep> plan = "prompt_assembly_002".equals(evalCase.id())
                    ? List.of(
                            new PlanStep(1, "knowledge_search", "搜索营收数据"),
                            new PlanStep(2, "calculator", "计算增长率"))
                    : Collections.emptyList();

            String topicId = "prompt_assembly_002".equals(evalCase.id()) ? "finance-reports" : null;

            String actualPrompt = (String) method.invoke(
                    agentOrchestrator, plan, "eval-prompt-" + evalCase.id(), topicId);

            String expectedSegmentsStr = promptSegments.entrySet().stream()
                    .filter(e -> e.getValue() != null)
                    .map(e -> "[" + e.getKey() + "] " + e.getValue())
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("(none)");

            Map<String, String> variables = Map.of(
                    "expected_segments", expectedSegmentsStr,
                    "actual_prompt", actualPrompt
            );

            JudgeResult judgeResult = evaluate(evalCase, variables);
            assertThat(judgeResult.passed())
                    .as("Prompt assembly for case '%s': %s", evalCase.id(), judgeResult.reasoning())
                    .isTrue();
        }
    }
}
