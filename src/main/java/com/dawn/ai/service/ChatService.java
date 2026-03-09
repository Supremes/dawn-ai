package com.dawn.ai.service;

import com.dawn.ai.agent.AgentOrchestrator;
import com.dawn.ai.dto.ChatRequest;
import com.dawn.ai.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final AgentOrchestrator agentOrchestrator;
    private final RagService ragService;
    private final ChatClient chatClient;

    public ChatResponse chat(ChatRequest request) {
        long start = System.currentTimeMillis();

        // Generate or reuse session ID for conversation continuity
        String sessionId = (request.getSessionId() != null && !request.getSessionId().isBlank())
                ? request.getSessionId()
                : UUID.randomUUID().toString();

        String userMessage = request.getMessage();

        // If RAG is enabled, prepend retrieved context to the user message
        if (request.isRagEnabled()) {
            String context = ragService.buildContext(userMessage);
            if (!context.isBlank()) {
                userMessage = context + "\n\nUser question: " + userMessage;
            }
        }

        String answer = agentOrchestrator.chat(sessionId, userMessage);

        return ChatResponse.builder()
                .sessionId(sessionId)
                .answer(answer)
                .durationMs(System.currentTimeMillis() - start)
                .model("gpt-4o")
                .build();
    }

    /** Simple one-shot chat without memory or tools */
    public String simpleChat(String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }
}
