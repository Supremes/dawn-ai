package com.dawn.ai.agent.trace;

public record AgentStep(
        int stepNumber,
        String toolName,
        Object toolInput,
        String toolOutput,
        long durationMs
) {}
