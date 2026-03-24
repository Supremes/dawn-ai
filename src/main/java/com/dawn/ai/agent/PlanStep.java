package com.dawn.ai.agent;

/**
 * A single planner-generated step.
 * This is intentionally a pure value object so structured output parsing can fail fast.
 */
public record PlanStep(
        Integer step,
        String action,
        String reason
) {
    public PlanStep {
        if (step == null || step < 1) {
            throw new IllegalArgumentException("step must be a positive integer");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
