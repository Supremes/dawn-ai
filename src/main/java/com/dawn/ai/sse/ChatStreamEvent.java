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
 * Event sequence per request: connected → plan? → step* → token* → done | error
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

    public static ChatStreamEvent step(String sessionId, AgentStep agentStep) {
        return ChatStreamEvent.builder()
                .event("step")
                .sessionId(sessionId)
                .timestamp(Instant.now().toString())
                .data(agentStep)
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
