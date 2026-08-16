package com.dawn.ai.agent.tools;

import java.util.Locale;

/**
 * 工具调用结果的统一分类。
 */
public enum ToolOutcomeStatus {
    SUCCESS(false, true),
    EMPTY(true, true),
    RETRYABLE_FAILURE(true, false),
    PERMANENT_FAILURE(true, false),
    REFUSED(true, false),
    PARTIAL(false, true);

    private final boolean recoverySignal;
    private final boolean executionSucceeded;

    ToolOutcomeStatus(boolean recoverySignal, boolean executionSucceeded) {
        this.recoverySignal = recoverySignal;
        this.executionSucceeded = executionSucceeded;
    }

    public boolean requiresRecovery() {
        return recoverySignal;
    }

    public boolean executionSucceeded() {
        return executionSucceeded;
    }

    public String stepStatus() {
        return name().toLowerCase(Locale.ROOT);
    }
}
