package com.dawn.ai.agent;

public record AgentStep(
        int stepNumber,
        String toolName,
        Object toolInput,
        String toolOutput,
        long durationMs
) {}
