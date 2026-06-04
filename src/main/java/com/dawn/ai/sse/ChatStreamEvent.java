package com.dawn.ai.sse;

import com.dawn.ai.agent.planning.PlanStep;
import com.dawn.ai.agent.trace.AgentStep;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Unified SSE event envelope.
 *
 * Event sequence per request: connected → plan_thinking* → plan? → thinking* → (step | sub_progress)* → token* → done | error
 *
 * {@code sub_progress} 事件由 sub-agent 内部 ReAct 步骤完成时冒泡发出，
 * 用于让前端在 sub-agent 长任务期间感知"还在跑"。前端可以选择不渲染本事件
 * （退化到只看最终 {@code step}），协议向后兼容。
 *
 * Each event is serialised as the JSON body of a SSE data line,
 * while the SSE event name mirrors the {@code event} field.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamEvent {

    /** Event type: connected, plan, step, token, done, error */
    private String event;
    private String sessionId;
    /** Per-stream monotonic sequence number (set by ChatService sink). */
    private int seq;
    private String timestamp;
    /** Event-specific payload; serialised as-is by Jackson. */
    private Object data;

    // ─── Factory helpers ───────────────────────────────────────────────────

    public static ChatStreamEvent connected(String sessionId, String streamId) {
        return ChatStreamEvent.builder()
                .event("connected")
                .sessionId(sessionId)
                .timestamp(Instant.now().toString())
                .data(Map.of("sessionId", sessionId, "streamId", streamId))
                .build();
    }

    public static ChatStreamEvent plan(String sessionId, List<PlanStep> steps, String summary) {
        return ChatStreamEvent.builder()
                .event("plan")
                .sessionId(sessionId)
                .timestamp(Instant.now().toString())
                .data(Map.of("steps", steps, "summary", summary))
                .build();
    }

        public static ChatStreamEvent planThinking(String sessionId, String content, int accumulatedLength) {
                return ChatStreamEvent.builder()
                                .event("plan_thinking")
                                .sessionId(sessionId)
                                .timestamp(Instant.now().toString())
                                .data(Map.of("content", content, "accumulatedLength", accumulatedLength))
                                .build();
        }

    public static ChatStreamEvent step(String sessionId, AgentStep agentStep) {
        return ChatStreamEvent.builder()
                .event("step")
                .sessionId(sessionId)
                .timestamp(Instant.now().toString())
                .data(agentStep)
                .build();
    }

    /**
     * Sub-agent 内部步骤完成时发出的轻量进度事件。
     *
     * @param parentToolName 主 Agent 中触发派发的工具名（通常 "DispatchSubAgentTool"）
     * @param subAgentType   sub-agent 类型（如 "research"）
     * @param subStep        sub-agent 内部完成的步骤序号（从 1 计）
     * @param currentTool    sub-agent 本步骤刚调用完的工具名
     */
    public static ChatStreamEvent subProgress(String sessionId, String parentToolName, String subAgentType,
                                              int subStep, String currentTool) {
        return ChatStreamEvent.builder()
                .event("sub_progress")
                .sessionId(sessionId)
                .timestamp(Instant.now().toString())
                .data(Map.of(
                        "parentToolName", parentToolName,
                        "subAgentType", subAgentType,
                        "subStep", subStep,
                        "currentTool", currentTool
                ))
                .build();
    }

    public static ChatStreamEvent token(String sessionId, String content, int accumulatedLength) {
        return ChatStreamEvent.builder()
                .event("token")
                .sessionId(sessionId)
                .timestamp(Instant.now().toString())
                .data(Map.of("content", content, "accumulatedLength", accumulatedLength))
                .build();
    }

    public static ChatStreamEvent thinking(String sessionId, String content, int accumulatedLength) {
        return ChatStreamEvent.builder()
                .event("thinking")
                .sessionId(sessionId)
                .timestamp(Instant.now().toString())
                .data(Map.of("content", content, "accumulatedLength", accumulatedLength))
                .build();
    }

    public static ChatStreamEvent done(String sessionId, String answer, List<AgentStep> steps,
                                       List<PlanStep> plan, long durationMs, String model) {
        return ChatStreamEvent.builder()
                .event("done")
                .sessionId(sessionId)
                .timestamp(Instant.now().toString())
                .data(Map.of(
                        "sessionId", sessionId,
                        "answer", answer,
                        "durationMs", durationMs,
                        "model", model,
                        "totalSteps", steps.size(),
                        "planSummary", formatPlanSummary(plan),
                        "steps", steps
                ))
                .build();
    }

    public static ChatStreamEvent error(String sessionId, String code, String message) {
        return ChatStreamEvent.builder()
                .event("error")
                .sessionId(sessionId)
                .timestamp(Instant.now().toString())
                .data(Map.of(
                        "code", code,
                        "message", message != null ? message : "Unknown error"
                ))
                .build();
    }

    private static String formatPlanSummary(List<PlanStep> plan) {
        if (plan == null || plan.isEmpty()) return "";
        return plan.stream()
                .map(s -> "步骤" + s.step() + ": " + s.action())
                .collect(Collectors.joining(" → "));
    }
}
