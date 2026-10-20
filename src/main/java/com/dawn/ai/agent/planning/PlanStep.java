package com.dawn.ai.agent.planning;

/**
 * planner 生成的单个步骤。
 * 故意做成纯 value object，这样结构化输出解析出问题时能 fail fast。
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
