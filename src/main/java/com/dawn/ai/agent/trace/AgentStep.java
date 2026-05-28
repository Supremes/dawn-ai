package com.dawn.ai.agent.trace;

import java.util.List;

/**
 * 单次工具调用的步骤记录。
 *
 * <p>{@code status} 与 {@code subSteps} 为 multi-agent 阶段新增字段：
 * 通过 5 参 delegating 构造器保持对所有旧调用站点的二进制/源码兼容
 * （默认 status="done"、subSteps=空列表）。
 *
 * @param status   {@code "done" | "error"}（未来可扩展 "running"，目前 aspect 仅在
 *                 结束时记录一次，因此实际只会出现 done/error）
 * @param subSteps 仅当本步骤是一次 sub-agent 派发（{@code DispatchSubAgentTool}）时填充，
 *                 内含 sub-agent 内部 ReAct 的步骤序列；其他工具一律空列表。
 *                 仅用于观测，不参与主 Agent 的步数计算。
 */
public record AgentStep(
        int stepNumber,
        String toolName,
        Object toolInput,
        String toolOutput,
        long durationMs,
        String status,
        List<AgentStep> subSteps
) {
    public AgentStep {
        if (status == null || status.isBlank()) {
            status = "done";
        }
        subSteps = subSteps == null ? List.of() : List.copyOf(subSteps);
    }

    /** 兼容 multi-agent 之前所有调用点：默认 status="done"、subSteps=空。 */
    public AgentStep(int stepNumber, String toolName, Object toolInput, String toolOutput, long durationMs) {
        this(stepNumber, toolName, toolInput, toolOutput, durationMs, "done", List.of());
    }
}
