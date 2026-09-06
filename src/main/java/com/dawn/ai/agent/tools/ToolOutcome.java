package com.dawn.ai.agent.tools;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 工具返回结果的统一执行语义。
 *
 * <p>该契约仅供编排、追踪与恢复策略使用，不进入返回给模型的 JSON。
 */
public interface ToolOutcome {

    @JsonIgnore
    ToolOutcomeStatus outcomeStatus();

    /**
     * 仅允许无副作用的工具显式开启自动重试。
     */
    @JsonIgnore
    default boolean safeToRetry() {
        return false;
    }
}
