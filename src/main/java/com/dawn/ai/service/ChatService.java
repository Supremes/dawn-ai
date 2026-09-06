package com.dawn.ai.service;

import com.dawn.ai.agent.orchestration.AgentOrchestrator;
import com.dawn.ai.config.AiAvailabilityChecker;
import com.dawn.ai.config.AiInteractionContext;
import com.dawn.ai.config.AiInteractionLogger;
import com.dawn.ai.dto.ChatRequest;
import com.dawn.ai.sse.ChatStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class ChatService {

    private final AgentOrchestrator agentOrchestrator;
    private final AiAvailabilityChecker aiAvailabilityChecker;
    private final ExecutorService chatStreamExecutor;
    private final ObjectMapper objectMapper;
    private final AiInteractionLogger aiInteractionLogger;
    private final ObservationRegistry observationRegistry;

    @Value("${spring.ai.openai.chat.options.model:qwen-plus}")
    private String model;

    @Value("${app.ai.stream.timeout-ms:120000}")
    private long streamTimeoutMs;

    public ChatService(AgentOrchestrator agentOrchestrator,
                       AiAvailabilityChecker aiAvailabilityChecker,
                       @Qualifier("chatStreamExecutor") ExecutorService chatStreamExecutor,
                       ObjectMapper objectMapper,
                       AiInteractionLogger aiInteractionLogger,
                       ObservationRegistry observationRegistry) {
        this.agentOrchestrator = agentOrchestrator;
        this.aiAvailabilityChecker = aiAvailabilityChecker;
        this.chatStreamExecutor = chatStreamExecutor;
        this.objectMapper = objectMapper;
        this.aiInteractionLogger = aiInteractionLogger;
        this.observationRegistry = observationRegistry;
    }

    private void writeLogicalChatRequest(String sessionId, ChatRequest request, boolean stream) {
        try {
            Map<String, Object> logicalRequest = new LinkedHashMap<>();
            logicalRequest.put("model", model);
            logicalRequest.put("stream", stream);
            logicalRequest.put("userMessage", request.getMessage());
            logicalRequest.put("topicId", request.getTopicId() == null ? "" : request.getTopicId());
            logicalRequest.put("sessionId", sessionId);
            logicalRequest.put("enabledTools",
                    request.getEnabledTools() == null ? "server-default" : request.getEnabledTools());
            logicalRequest.put("enabledSkills",
                    request.getEnabledSkills() == null ? "server-default" : request.getEnabledSkills());
            String body = objectMapper.writeValueAsString(logicalRequest);
            String label = "Stream chat → " + truncate(request.getMessage(), 80);
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
        Observation agentObservation = Observation.createNotStarted("ai.agent.stream", observationRegistry)
            .contextualName("agent chat")
            .highCardinalityKeyValue(KeyValue.of("langfuse.observation.type", "agent"))
            .highCardinalityKeyValue(KeyValue.of("langfuse.trace.name", "agent-chat"))
            .highCardinalityKeyValue(KeyValue.of("langfuse.session.id", sessionId))
            .highCardinalityKeyValue(KeyValue.of("langfuse.observation.input", request.getMessage()))
            .highCardinalityKeyValue(KeyValue.of("langfuse.trace.input", request.getMessage()))
            .start();

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
                AiInteractionContext.set(
                        sessionId,
                        request.getEnabledTools(),
                        request.getEnabledSkills());
                long startedAt = System.currentTimeMillis();
                writeLogicalChatRequest(sessionId, request, true);
                try (Observation.Scope ignored = agentObservation.openScope()) {
                    sendEvent(emitter, ChatStreamEvent.connected(sessionId, streamId), seqCounter);
                    agentOrchestrator.streamChat(sessionId, request.getMessage(), request.getTopicId(),
                            event -> {
                                sendEvent(emitter, event, seqCounter);
                                if ("done".equals(event.getEvent())) {
                                    recordAgentOutput(agentObservation, event.getData());
                                    writeLogicalChatResponse(sessionId, event.getData(),
                                            System.currentTimeMillis() - startedAt);
                                } else if ("error".equals(event.getEvent())) {
                                    recordAgentError(agentObservation, event.getData());
                                    writeLogicalChatError(sessionId, event.getData());
                                }
                            },
                            cancelled::get);
                } catch (Exception e) {
                    agentObservation.error(e);
                    log.error("[ChatService] Unexpected error in stream thread, sessionId={}", sessionId, e);
                    sendEvent(emitter, ChatStreamEvent.error(sessionId, "INTERNAL_ERROR", e.getMessage()), seqCounter);
                } finally {
                    agentObservation.stop();
                    AiInteractionContext.clear();
                    try { emitter.complete(); } catch (IllegalStateException ignored) {}
                }
            });
        } catch (RejectedExecutionException e) {
            agentObservation.error(e);
            agentObservation.stop();
            log.warn("[ChatService] chatStreamExecutor at capacity, rejecting request, sessionId={}", sessionId);
            sendEvent(emitter, ChatStreamEvent.error(sessionId, "CAPACITY_EXCEEDED",
                    "Server is busy, please retry later"), seqCounter);
            try { emitter.complete(); } catch (IllegalStateException ignored) {}
        }

        return emitter;
    }

    private void recordAgentOutput(Observation observation, Object doneData) {
        if (!(doneData instanceof Map<?, ?> data)) {
            return;
        }
        Object answer = data.get("answer");
        if (answer == null) {
            return;
        }
        String output = String.valueOf(answer);
        observation.highCardinalityKeyValue(KeyValue.of("langfuse.observation.output", output));
        observation.highCardinalityKeyValue(KeyValue.of("langfuse.trace.output", output));
    }

    private void recordAgentError(Observation observation, Object errorData) {
        String message = "Agent stream failed";
        if (errorData instanceof Map<?, ?> data && data.get("message") != null) {
            message = String.valueOf(data.get("message"));
        }
        observation.error(new IllegalStateException(message));
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

}
