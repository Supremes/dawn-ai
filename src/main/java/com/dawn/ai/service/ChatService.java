package com.dawn.ai.service;

import com.dawn.ai.agent.orchestration.AgentOrchestrator;
import com.dawn.ai.agent.orchestration.AgentResult;
import com.dawn.ai.agent.planning.PlanStep;
import com.dawn.ai.config.AiAvailabilityChecker;
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
                       ObjectMapper objectMapper) {
        this.agentOrchestrator = agentOrchestrator;
        this.chatClient = chatClient;
        this.aiAvailabilityChecker = aiAvailabilityChecker;
        this.chatStreamExecutor = chatStreamExecutor;
        this.objectMapper = objectMapper;
    }

    public ChatResponse chat(ChatRequest request) {
        long start = System.currentTimeMillis();

        aiAvailabilityChecker.ensureConfigured();

        String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
                ? request.getSessionId()
                : UUID.randomUUID().toString();

        String userMessage = request.getMessage();

        AgentResult result = agentOrchestrator.chat(sessionId, userMessage);

        return ChatResponse.builder()
                .sessionId(sessionId)
                .answer(result.finalAnswer())
                .steps(showSteps ? result.steps() : null)
                .planSummary(formatPlanSummary(result.plan()))
                .totalSteps(result.steps().size())
                .durationMs(System.currentTimeMillis() - start)
                .model(model)
                .build();
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
     * <p>Events are published in order: {@code connected → plan? → step* → token* → done | error}.
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
        String streamId = UUID.randomUUID().toString();

        emitter.onTimeout(() -> {
            log.warn("[ChatService] SSE timeout, sessionId={}", sessionId);
            sendEvent(emitter, ChatStreamEvent.error(sessionId, "TIMEOUT", "Response timed out"), seqCounter);
            emitter.complete();
        });
        emitter.onError(e -> log.warn("[ChatService] SSE transport error, sessionId={}", sessionId, e));

        chatStreamExecutor.execute(() -> {
            try {
                sendEvent(emitter, ChatStreamEvent.connected(sessionId, streamId), seqCounter);
                agentOrchestrator.streamChat(sessionId, request.getMessage(),
                        event -> sendEvent(emitter, event, seqCounter));
                emitter.complete();
            } catch (Exception e) {
                log.error("[ChatService] Unexpected error in stream thread, sessionId={}", sessionId, e);
                sendEvent(emitter, ChatStreamEvent.error(sessionId, "INTERNAL_ERROR", e.getMessage()), seqCounter);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, ChatStreamEvent event, AtomicInteger seqCounter) {
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

    /** Formats the plan as a concise human-readable summary, e.g. "步骤1: weatherTool → 步骤2: 完成". */
    private String formatPlanSummary(List<PlanStep> plan) {
        if (plan == null || plan.isEmpty()) return "";
        return plan.stream()
                .map(s -> "步骤" + s.step() + ": " + s.action())
                .collect(Collectors.joining(" → "));
    }
}
