package com.dawn.ai.evaluation.base;

import com.dawn.ai.agent.planning.PlanStep;
import com.dawn.ai.agent.trace.AgentStep;
import com.dawn.ai.evaluation.judge.JudgeResult;

import java.util.List;

public record EvaluationCaseResult(
        EvaluationCase evaluationCase,
        JudgeResult result,
        String sessionId,
        List<String> expectedTools,
        List<String> actualTools,
        List<String> forbiddenTools,
        boolean allowExtraTools,
        String finalAnswer,
        List<PlanStep> plannerSteps,
        List<AgentStep> agentSteps
) {
    public EvaluationCaseResult {
        expectedTools = expectedTools == null ? List.of() : List.copyOf(expectedTools);
        actualTools = actualTools == null ? List.of() : List.copyOf(actualTools);
        forbiddenTools = forbiddenTools == null ? List.of() : List.copyOf(forbiddenTools);
        finalAnswer = finalAnswer == null ? "" : finalAnswer;
        plannerSteps = plannerSteps == null ? List.of() : List.copyOf(plannerSteps);
        agentSteps = agentSteps == null ? List.of() : List.copyOf(agentSteps);
    }
}
