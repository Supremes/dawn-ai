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
        List<AgentStep> agentSteps,
        List<String> expectedDocIds,
        List<String> retrievedDocIds,
        double recallAtK,
        double precisionAtK,
        double hitAtK,
        double mrrAtK,
        double ndcgAtK,
        String retrievalStrategy
) {
    public EvaluationCaseResult {
        expectedTools = expectedTools == null ? List.of() : List.copyOf(expectedTools);
        actualTools = actualTools == null ? List.of() : List.copyOf(actualTools);
        forbiddenTools = forbiddenTools == null ? List.of() : List.copyOf(forbiddenTools);
        finalAnswer = finalAnswer == null ? "" : finalAnswer;
        plannerSteps = plannerSteps == null ? List.of() : List.copyOf(plannerSteps);
        agentSteps = agentSteps == null ? List.of() : List.copyOf(agentSteps);
        expectedDocIds = expectedDocIds == null ? List.of() : List.copyOf(expectedDocIds);
        retrievedDocIds = retrievedDocIds == null ? List.of() : List.copyOf(retrievedDocIds);
        retrievalStrategy = retrievalStrategy == null ? "" : retrievalStrategy;
    }

    public static EvaluationCaseResult forToolSelection(
            EvaluationCase evaluationCase,
            JudgeResult result,
            String sessionId,
            List<String> expectedTools,
            List<String> actualTools,
            List<String> forbiddenTools,
            boolean allowExtraTools,
            String finalAnswer,
            List<PlanStep> plannerSteps,
            List<AgentStep> agentSteps) {
        return new EvaluationCaseResult(
                evaluationCase, result, sessionId,
                expectedTools, actualTools, forbiddenTools, allowExtraTools,
                finalAnswer, plannerSteps, agentSteps,
                List.of(), List.of(), 0.0, 0.0, 0.0, 0.0, 0.0, null);
    }

    public static EvaluationCaseResult forRagRecall(
            EvaluationCase evaluationCase,
            JudgeResult result,
            String sessionId,
            String finalAnswer,
            List<String> expectedDocIds,
            List<String> retrievedDocIds,
            double recallAtK,
            double precisionAtK,
            double hitAtK,
            double mrrAtK,
            double ndcgAtK,
            String retrievalStrategy) {
        return new EvaluationCaseResult(
                evaluationCase, result, sessionId,
                List.of(), List.of(), List.of(), false,
                finalAnswer, List.of(), List.of(),
                expectedDocIds, retrievedDocIds,
                recallAtK, precisionAtK, hitAtK, mrrAtK, ndcgAtK,
                retrievalStrategy);
    }
}
