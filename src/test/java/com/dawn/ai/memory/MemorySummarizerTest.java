 package com.dawn.ai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemorySummarizerTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ApplicationEventPublisher eventPublisher;
    private MemorySummarizer summarizer;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        summarizer = new MemorySummarizer(chatClient, eventPublisher);
    }

    @Test
    void onSummarizationRequest_publishesConsolidationEventWithSummary() {
        when(callSpec.content()).thenReturn("用户讨论了天气问题，询问了北京气温。");

        SummarizationRequestEvent event = new SummarizationRequestEvent(
                "session1",
                List.of(
                        Map.of("role", "user", "content", "北京今天天气如何？"),
                        Map.of("role", "assistant", "content", "北京今天晴，25度。")
                )
        );

        summarizer.onSummarizationRequest(event);

        verify(eventPublisher).publishEvent(argThat((Object e) ->
                e instanceof ConsolidationRequestEvent cre &&
                "session1".equals(cre.result().sessionId()) &&
                "用户讨论了天气问题，询问了北京气温。".equals(cre.result().text()) &&
                cre.result().importanceScore() == 0.5
        ));
    }

    @Test
    void onSummarizationRequest_usesRawTextFallbackWhenLLMFails() {
        when(callSpec.content()).thenThrow(new RuntimeException("LLM timeout"));

        SummarizationRequestEvent event = new SummarizationRequestEvent(
                "session2",
                List.of(Map.of("role", "user", "content", "test message"))
        );

        summarizer.onSummarizationRequest(event);

        verify(eventPublisher).publishEvent(argThat((Object e) ->
                e instanceof ConsolidationRequestEvent cre &&
                cre.result().importanceScore() < 0.4
        ));
    }
}
