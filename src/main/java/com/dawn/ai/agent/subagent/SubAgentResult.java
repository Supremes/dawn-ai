package com.dawn.ai.agent.subagent;

import com.dawn.ai.agent.trace.AgentStep;

import java.util.List;

/**
 * Sub-agent 执行结果。
 *
 * <p>{@link #summary} 是主 Agent 在 ReAct 循环里直接当 tool result 使用的回答文本，
 * 在 {@link SubAgentExecutionStatus#PARTIAL_SUCCESS} 时为已完成步骤的拼接摘要
 * （不再额外发 LLM 调用，避免失败时再花 token 与再失败的风险）。
 *
 * <p>{@link #subSteps} 仅用于观测（前端嵌套展示 / Langfuse trace），<b>不</b>合并入主
 * {@link com.dawn.ai.agent.trace.StepCollector}，保持主 Agent 视角的 step 计数干净。
 */
public record SubAgentResult(
        String summary,
        List<AgentStep> subSteps,
        SubAgentExecutionStatus status,
        String failureReason,
        long durationMs
) {
    public SubAgentResult {
        subSteps = subSteps == null ? List.of() : List.copyOf(subSteps);
    }

    public static SubAgentResult success(String summary, List<AgentStep> subSteps, long durationMs) {
        return new SubAgentResult(summary, subSteps, SubAgentExecutionStatus.SUCCESS, null, durationMs);
    }

    public static SubAgentResult partial(String partialSummary, List<AgentStep> subSteps,
                                          String failureReason, long durationMs) {
        return new SubAgentResult(partialSummary, subSteps, SubAgentExecutionStatus.PARTIAL_SUCCESS,
                failureReason, durationMs);
    }

    public static SubAgentResult failed(String failureReason, List<AgentStep> subSteps, long durationMs) {
        return new SubAgentResult(
                "（子 Agent 执行失败，原因：" + failureReason + "）",
                subSteps,
                SubAgentExecutionStatus.FAILED,
                failureReason,
                durationMs);
    }
}
