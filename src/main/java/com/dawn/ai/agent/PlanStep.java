package com.dawn.ai.agent;

import lombok.Data;

/**
 * A single step in the pre-execution plan.
 * Uses a regular class (not record) because completed/result are mutated during execution.
 */
@Data
public class PlanStep {
    private final int stepNumber;
    private final String action;   // tool name or "finish" for the last step
    private final String reason;
    private boolean completed = false;
    private String result;
}
