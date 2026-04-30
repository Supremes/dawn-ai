package com.dawn.ai.memory;

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

    private static final String PROMPT_TEMPLATE =
            "以下是一段对话历史，请将其压缩成简洁的摘要（100字以内），保留关键信息、用户偏好和重要事实。\n" +
            "对话历史:\n%s\n摘要:";

    @EventListener
    @Async
    public void onSummarizationRequest(SummarizationRequestEvent event) {
        String historyText = event.messages().stream()
                .map(m -> m.get("role") + ": " + m.get("content"))
                .collect(Collectors.joining("\n"));

        SummaryResult result;
        try {
            String summary = chatClient.prompt()
                    .user(PROMPT_TEMPLATE.formatted(historyText))
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
