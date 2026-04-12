package com.dawn.ai.agent.orchestration;

import com.dawn.ai.agent.planning.PlanStep;
import com.dawn.ai.agent.trace.AgentStep;

import java.util.List;

/**
 * Return type of AgentOrchestrator.chat(), replacing the bare String.
 */
public record AgentResult(
        String finalAnswer,
        List<AgentStep> steps,
        List<PlanStep> plan
) {}
