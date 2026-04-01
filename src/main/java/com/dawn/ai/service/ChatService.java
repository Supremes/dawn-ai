package com.dawn.ai.service;

import com.dawn.ai.agent.AgentResult;
import com.dawn.ai.agent.AgentOrchestrator;
import com.dawn.ai.agent.plan.PlanStep;
import com.dawn.ai.config.AiAvailabilityChecker;
import com.dawn.ai.dto.ChatRequest;
import com.dawn.ai.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final AgentOrchestrator agentOrchestrator;
    private final ChatClient chatClient;
    private final AiAvailabilityChecker aiAvailabilityChecker;

    @Value("${app.ai.react.show-steps:false}")
    private boolean showSteps;

    @Value("${spring.ai.openai.chat.options.model:qwen-plus}")
    private String model;

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

    /** Formats the plan as a concise human-readable summary, e.g. "步骤1: weatherTool → 步骤2: 完成". */
    private String formatPlanSummary(List<PlanStep> plan) {
        if (plan == null || plan.isEmpty()) return "";
        return plan.stream()
                .map(s -> "步骤" + s.step() + ": " + s.action())
                .collect(Collectors.joining(" → "));
    }
}
