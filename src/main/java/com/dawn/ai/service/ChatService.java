package com.dawn.ai.service;

import com.dawn.ai.agent.orchestration.AgentOrchestrator;
import com.dawn.ai.agent.orchestration.AgentResult;
import com.dawn.ai.agent.planning.PlanStep;
import com.dawn.ai.config.AiAvailabilityChecker;
import com.dawn.ai.config.AiInteractionContext;
import com.dawn.ai.config.AiInteractionLogger;
import com.dawn.ai.dto.ChatRequest;
import com.dawn.ai.dto.ChatResponse;
import com.dawn.ai.sse.ChatStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatService {

    private final AgentOrchestrator agentOrchestrator;
    private final ChatClient chatClient;
    private final AiAvailabilityChecker aiAvailabilityChecker;
    private final ExecutorService chatStreamExecutor;
    private final ObjectMapper objectMapper;
    private final AiInteractionLogger aiInteractionLogger;

    @Value("${app.ai.react.show-steps:false}")
    private boolean showSteps;

    @Value("${spring.ai.openai.chat.options.model:qwen-plus}")
    private String model;

    @Value("${app.ai.stream.timeout-ms:120000}")
    private long streamTimeoutMs;

    public ChatService(AgentOrchestrator agentOrchestrator,
                       ChatClient chatClient,
                       AiAvailabilityChecker aiAvailabilityChecker,
                       @Qualifier("chatStreamExecutor") ExecutorService chatStreamExecutor,
                       ObjectMapper objectMapper,
                       AiInteractionLogger aiInteractionLogger) {
        this.agentOrchestrator = agentOrchestrator;
        this.chatClient = chatClient;
        this.aiAvailabilityChecker = aiAvailabilityChecker;
        this.chatStreamExecutor = chatStreamExecutor;
        this.objectMapper = objectMapper;
        this.aiInteractionLogger = aiInteractionLogger;
    }

    public ChatResponse chat(ChatRequest request) {
        long start = System.currentTimeMillis();

        aiAvailabilityChecker.ensureConfigured();

        String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
                ? request.getSessionId()
                : UUID.randomUUID().toString();

        String userMessage = request.getMessage();

        AiInteractionContext.setSessionId(sessionId);
        writeLogicalChatRequest(sessionId, request, false);
        try {
            AgentResult result = agentOrchestrator.chat(sessionId, userMessage, request.getTopicId());

            ChatResponse response = ChatResponse.builder()
                    .sessionId(sessionId)
                    .answer(result.finalAnswer())
                    .steps(showSteps ? result.steps() : null)
                    .planSummary(formatPlanSummary(result.plan()))
                    .totalSteps(result.steps().size())
                    .durationMs(System.currentTimeMillis() - start)
                    .model(model)
                    .build();

            writeLogicalSyncChatResponse(sessionId, result, response);
            return response;
        } finally {
            AiInteractionContext.clear();
        }
    }

    private void writeLogicalChatRequest(String sessionId, ChatRequest request, boolean stream) {
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "model", model,
                    "stream", stream,
                    "userMessage", request.getMessage(),
                    "topicId", request.getTopicId() == null ? "" : request.getTopicId(),
                    "sessionId", sessionId
            ));
            String label = (stream ? "Stream chat → " : "Sync chat → ") + truncate(request.getMessage(), 80);
            aiInteractionLogger.logLogical(sessionId, "request", label, body, null);
        } catch (Exception e) {
            log.warn("[ChatService] failed to write logical request: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void writeLogicalChatResponse(String sessionId, Object doneData, long latencyMs) {
        try {
            java.util.Map<String, Object> data = doneData instanceof java.util.Map
                    ? (java.util.Map<String, Object>) doneData
                    : java.util.Map.of();
            String answer = String.valueOf(data.getOrDefault("answer", ""));
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "model", data.getOrDefault("model", model),
                    "answer", answer,
                    "totalSteps", data.getOrDefault("totalSteps", 0),
                    "planSummary", data.getOrDefault("planSummary", ""),
                    "durationMs", data.getOrDefault("durationMs", latencyMs)
            ));
            aiInteractionLogger.logLogical(sessionId, "response", "Stream chat answer", body, latencyMs);
        } catch (Exception e) {
            log.warn("[ChatService] failed to write logical response: {}", e.getMessage());
        }
    }

    private void writeLogicalSyncChatResponse(String sessionId, AgentResult result, ChatResponse response) {
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "model", response.getModel() == null ? model : response.getModel(),
                    "answer", result.finalAnswer() == null ? "" : result.finalAnswer(),
                    "totalSteps", response.getTotalSteps(),
                    "planSummary", response.getPlanSummary() == null ? "" : response.getPlanSummary(),
                    "durationMs", response.getDurationMs()
            ));
            aiInteractionLogger.logLogical(sessionId, "response", "Sync chat answer", body, response.getDurationMs());
        } catch (Exception e) {
            log.warn("[ChatService] failed to write sync logical response: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void writeLogicalChatError(String sessionId, Object errorData) {
        try {
            java.util.Map<String, Object> data = errorData instanceof java.util.Map
                    ? (java.util.Map<String, Object>) errorData
                    : java.util.Map.of();
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "code", data.getOrDefault("code", "ERROR"),
                    "message", data.getOrDefault("message", "")
            ));
            aiInteractionLogger.logLogical(sessionId, "response", "Stream chat error", body, null);
        } catch (Exception e) {
            log.warn("[ChatService] failed to write logical error: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Simple one-shot chat without memory or tools */
    public String simpleChat(String message) {
        aiAvailabilityChecker.ensureConfigured();

        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

    /**
     * Creates an {@link SseEmitter} and asynchronously streams the agent response.
     *
     * <p>Events are published in order:
     * {@code connected → plan_thinking* → plan? → thinking* → step* → token* → done | error}
     * (see {@link com.dawn.ai.sse.ChatStreamEvent} for the canonical sequence).
     * The caller (controller) returns the emitter to Spring MVC immediately; the actual
     * processing happens on the {@code chatStreamExecutor} thread pool.
     */
    public SseEmitter streamChat(ChatRequest request) {
        aiAvailabilityChecker.ensureConfigured();

        String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
                ? request.getSessionId()
                : UUID.randomUUID().toString();

        SseEmitter emitter = new SseEmitter(streamTimeoutMs);
        AtomicInteger seqCounter = new AtomicInteger(0);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        String streamId = UUID.randomUUID().toString();

        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> {
            log.warn("[ChatService] SSE timeout, sessionId={}", sessionId);
            cancelled.set(true);
            sendEvent(emitter, ChatStreamEvent.error(sessionId, "TIMEOUT", "Response timed out"), seqCounter);
            try { emitter.complete(); } catch (IllegalStateException ignored) {}
        });
        emitter.onError(e -> {
            log.warn("[ChatService] SSE transport error, sessionId={}", sessionId, e);
            cancelled.set(true);
        });

        try {
            chatStreamExecutor.execute(() -> {
                AiInteractionContext.setSessionId(sessionId);
                long startedAt = System.currentTimeMillis();
                writeLogicalChatRequest(sessionId, request, true);
                try {
                    sendEvent(emitter, ChatStreamEvent.connected(sessionId, streamId), seqCounter);
                    agentOrchestrator.streamChat(sessionId, request.getMessage(), request.getTopicId(),
                            event -> {
                                sendEvent(emitter, event, seqCounter);
                                if ("done".equals(event.getEvent())) {
                                    writeLogicalChatResponse(sessionId, event.getData(),
                                            System.currentTimeMillis() - startedAt);
                                } else if ("error".equals(event.getEvent())) {
                                    writeLogicalChatError(sessionId, event.getData());
                                }
                            },
                            cancelled::get);
                } catch (Exception e) {
                    log.error("[ChatService] Unexpected error in stream thread, sessionId={}", sessionId, e);
                    sendEvent(emitter, ChatStreamEvent.error(sessionId, "INTERNAL_ERROR", e.getMessage()), seqCounter);
                } finally {
                    AiInteractionContext.clear();
                    try { emitter.complete(); } catch (IllegalStateException ignored) {}
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("[ChatService] chatStreamExecutor at capacity, rejecting request, sessionId={}", sessionId);
            sendEvent(emitter, ChatStreamEvent.error(sessionId, "CAPACITY_EXCEEDED",
                    "Server is busy, please retry later"), seqCounter);
            try { emitter.complete(); } catch (IllegalStateException ignored) {}
        }

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, ChatStreamEvent event, AtomicInteger seqCounter) {
        synchronized (emitter) {
            event.setSeq(seqCounter.getAndIncrement());
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getEvent())
                        .id(String.valueOf(event.getSeq()))
                        .data(objectMapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                log.warn("[ChatService] Failed to send SSE event type={}, sessionId={}: {}",
                        event.getEvent(), event.getSessionId(), e.getMessage());
            }
        }
    }

    /** Formats the plan as a concise human-readable summary, e.g. "步骤1: weatherTool → 步骤2: 完成". */
    private String formatPlanSummary(List<PlanStep> plan) {
        if (plan == null || plan.isEmpty()) return "";
        return plan.stream()
                .map(s -> "步骤" + s.step() + ": " + s.action())
                .collect(Collectors.joining(" → "));
    }
}
