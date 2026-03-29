package com.dawn.ai.agent;

import com.dawn.ai.agent.plan.PlanStep;

import java.util.List;

/**
 * Return type of AgentOrchestrator.chat(), replacing the bare String.
 */
public record AgentResult(
        String finalAnswer,
        List<AgentStep> steps,
        List<PlanStep> plan
) {}
