package com.dawn.ai.memory;

import com.dawn.ai.config.PromptManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemorySummarizer {

    private final ChatClient chatClient;
    private final ApplicationEventPublisher eventPublisher;
    private final PromptManager promptManager;

    @EventListener
    @Async
    public void onSummarizationRequest(SummarizationRequestEvent event) {
        String historyText = event.messages().stream()
                .map(m -> m.getOrDefault("role", "") + ": " + m.getOrDefault("content", ""))
                .collect(Collectors.joining("\n"));

        SummaryResult result;
        try {
            String summary = chatClient.prompt()
                    .user(promptManager.render("memory-summarize", Map.of("historyText", historyText)))
                    .call()
                    .content();
            result = new SummaryResult(event.sessionId(), summary, 0.5, Instant.now());
            log.info("[MemorySummarizer] Summarized {} messages for session={}", event.messages().size(), event.sessionId());
        } catch (Exception e) {
            log.warn("[MemorySummarizer] LLM failed for session={}, using raw fallback: {}", event.sessionId(), e.getMessage());
            result = new SummaryResult(event.sessionId(), historyText, 0.3, Instant.now());
        }
        eventPublisher.publishEvent(new ConsolidationRequestEvent(result));
    }
}
