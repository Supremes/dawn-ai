package com.dawn.ai.agent;

import com.dawn.ai.agent.tools.CalculatorTool;
import com.dawn.ai.agent.tools.WeatherTool;
import com.dawn.ai.service.MemoryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent Orchestrator — the brain of the ReAct loop.
 *
 * Design Analogy:
 * Think of this as a Thread Pool Manager using ReentrantLock + Condition:
 *  - The LLM is the "main thread" that decides which "worker thread" (Tool) to invoke.
 *  - Tools are like Callable tasks submitted to an executor — they run, return results,
 *    and the LLM synthesizes the final answer from all tool outputs.
 *  - Memory history is the shared state, protected conceptually like a concurrent queue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final ChatClient chatClient;
    private final MemoryService memoryService;
    private final WeatherTool weatherTool;
    private final CalculatorTool calculatorTool;
    private final MeterRegistry meterRegistry;

    public String chat(String sessionId, String userMessage) {
        return Timer.builder("ai.agent.chat.duration")
                .tag("session", "anonymous")
                .register(meterRegistry)
                .record(() -> doChat(sessionId, userMessage));
    }

    private String doChat(String sessionId, String userMessage) {
        // 1. Build conversation history without duplicating the current turn
        List<Message> history = buildHistory(sessionId);

        // 2. Call LLM with prior history plus the current user turn
        String response = chatClient.prompt()
                .messages(history)
                .user(userMessage)
                .toolNames("weatherTool", "calculatorTool")
                .call()
                .content();

        // 3. Persist the completed turn after a successful model call
        memoryService.addMessage(sessionId, "user", userMessage);
        memoryService.addMessage(sessionId, "assistant", response);

        log.info("[AgentOrchestrator] session={}, userMsg={}, responseLen={}",
                sessionId, userMessage.substring(0, Math.min(50, userMessage.length())), response.length());

        return response;
    }

    private List<Message> buildHistory(String sessionId) {
        List<Map<String, String>> rawHistory = memoryService.getHistory(sessionId);
        List<Message> messages = new ArrayList<>();
        for (Map<String, String> entry : rawHistory) {
            String role = entry.get("role");
            String content = entry.get("content");
            if ("user".equals(role)) {
                messages.add(new UserMessage(content));
            } else if ("assistant".equals(role)) {
                messages.add(new AssistantMessage(content));
            }
        }
        return messages;
    }
}
