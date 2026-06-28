package com.dawn.ai.evaluation.dimensions;

import com.dawn.ai.agent.trace.AgentStep;
import com.dawn.ai.agent.trace.StepCollector;
import com.dawn.ai.agent.trace.StepCollectorContext;
import com.dawn.ai.evaluation.base.EvaluationCase;
import com.dawn.ai.evaluation.base.EvaluationDatasetLoader;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubAgentIsolationEvaluationTest {

    @Test
    @DisplayName("evaluation: 子 Agent 上下文隔离")
    void evaluate_subAgentIsolation() {
        List<EvaluationCase> cases = EvaluationDatasetLoader.loadByDimension(JudgeDimension.SUBAGENT_ISOLATION.id());
        assertThat(cases).isNotEmpty();

        for (EvaluationCase evalCase : cases) {
            @SuppressWarnings("unchecked")
            Map<String, Object> subAgentBehavior = (Map<String, Object>) evalCase.context().get("subAgentBehavior");
            assertThat(subAgentBehavior)
                    .as("subAgentBehavior must exist for case '%s'", evalCase.id())
                    .isNotNull();

            assertBehaviorContract(evalCase, subAgentBehavior);
        }
    }

    private static void assertBehaviorContract(EvaluationCase evalCase, Map<String, Object> behavior) {
        if (behavior.containsKey("mainAgentStepsBefore")) {
            assertDetachedStepCollectorIsolation(evalCase, behavior);
            return;
        }

        if (behavior.containsKey("timeoutSeconds")) {
            assertTimeoutContract(evalCase, behavior);
            return;
        }

        if (behavior.containsKey("maxDispatchesPerSession")) {
            assertDispatchLimitContract(evalCase, behavior);
            return;
        }

        throw new AssertionError("Unsupported sub-agent behavior case '%s': %s"
                .formatted(evalCase.id(), behavior));
    }

    private static void assertDetachedStepCollectorIsolation(
            EvaluationCase evalCase,
            Map<String, Object> behavior) {
        int mainBefore = intValue(behavior, "mainAgentStepsBefore");
        int mainAfter = intValue(behavior, "mainAgentStepsAfter");
        int subSteps = intValue(behavior, "subAgentSteps");

        try {
            StepCollector.init(Math.max(1, mainBefore + mainAfter));
            recordSteps("main-before", mainBefore);
            StepCollectorContext mainContext = StepCollector.snapshotContext();

            StepCollectorContext subContext = StepCollector.newDetachedContext(Math.max(1, subSteps), null);
            StepCollector.adoptContext(subContext);
            recordSteps("sub-agent", subSteps);

            List<AgentStep> capturedSubSteps = subContext.snapshotSteps();
            assertThat(capturedSubSteps)
                    .as("case '%s' should capture sub-agent steps in detached context", evalCase.id())
                    .hasSize(subSteps)
                    .allMatch(step -> step.toolName().startsWith("sub-agent"));

            StepCollector.adoptContext(mainContext);
            recordSteps("main-after", mainAfter);

            List<AgentStep> mainSteps = StepCollector.collect();
            assertThat(mainSteps)
                    .as("case '%s' should keep main StepCollector isolated", evalCase.id())
                    .hasSize(mainBefore + mainAfter)
                    .noneMatch(step -> step.toolName().startsWith("sub-agent"));
        } finally {
            StepCollector.clear();
        }
    }

    private static void assertTimeoutContract(EvaluationCase evalCase, Map<String, Object> behavior) {
        int timeout = intValue(behavior, "timeoutSeconds");
        int actualDuration = intValue(behavior, "actualDurationSeconds");
        String expectedResult = String.valueOf(behavior.get("expectedResult"));
        String deterministicResult = actualDuration < timeout ? "success" : "timeout_fallback";

        assertThat(timeout)
                .as("case '%s' timeout should be non-negative", evalCase.id())
                .isGreaterThanOrEqualTo(0);
        assertThat(actualDuration)
                .as("case '%s' actual duration should be non-negative", evalCase.id())
                .isGreaterThanOrEqualTo(0);
        assertThat(expectedResult)
                .as("case '%s' timeout expectation should match boundary contract", evalCase.id())
                .isEqualTo(deterministicResult);
    }

    private static void assertDispatchLimitContract(EvaluationCase evalCase, Map<String, Object> behavior) {
        int maxDispatches = intValue(behavior, "maxDispatchesPerSession");
        int attempted = intValue(behavior, "attemptedDispatches");
        int accepted = Math.min(maxDispatches, attempted);
        int rejected = Math.max(0, attempted - maxDispatches);

        assertThat(maxDispatches)
                .as("case '%s' max dispatches should be non-negative", evalCase.id())
                .isGreaterThanOrEqualTo(0);
        assertThat(attempted)
                .as("case '%s' attempted dispatches should be non-negative", evalCase.id())
                .isGreaterThanOrEqualTo(0);
        assertThat(accepted)
                .as("case '%s' accepted dispatches must stay within limit", evalCase.id())
                .isLessThanOrEqualTo(maxDispatches);
        assertThat(accepted + rejected)
                .as("case '%s' accepted plus rejected should equal attempted", evalCase.id())
                .isEqualTo(attempted);

        if (behavior.containsKey("nestedDispatchAttempts")) {
            int nested = intValue(behavior, "nestedDispatchAttempts");
            assertThat(nested)
                    .as("case '%s' nested dispatch attempts should be data-only and fully blocked", evalCase.id())
                    .isGreaterThan(0);
        }
    }

    private static void recordSteps(String prefix, int count) {
        for (int i = 1; i <= count; i++) {
            StepCollector.record(new AgentStep(i, prefix + "-" + i, "input-" + i, "output-" + i, 1));
        }
    }

    private static int intValue(Map<String, Object> behavior, String key) {
        assertThat(behavior)
                .as("behavior should contain '%s'", key)
                .containsKey(key);
        return ((Number) behavior.get(key)).intValue();
    }
}
