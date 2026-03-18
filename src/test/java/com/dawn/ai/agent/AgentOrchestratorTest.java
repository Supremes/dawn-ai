package com.dawn.ai.agent;

import com.dawn.ai.agent.tools.CalculatorTool;
import com.dawn.ai.agent.tools.WeatherTool;
import com.dawn.ai.service.MemoryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorTest {

    private AgentOrchestrator agentOrchestrator;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    @Mock
    private MemoryService memoryService;

    @Mock
    private WeatherTool weatherTool;

    @Mock
    private CalculatorTool calculatorTool;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        agentOrchestrator = new AgentOrchestrator(
                chatClient,
                memoryService,
                weatherTool,
                calculatorTool,
                new SimpleMeterRegistry()
        );
    }

    @Test
    void shouldSendCurrentUserMessageOnlyOnce() {
        when(memoryService.getHistory("session-1"))
                .thenReturn(List.of(Map.of("role", "assistant", "content", "previous reply")));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(any(List.class))).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolNames(any(String[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("final answer");

        String response = agentOrchestrator.chat("session-1", "current question");

        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(requestSpec).messages(historyCaptor.capture());
        verify(requestSpec).user("current question");
        verify(memoryService).addMessage("session-1", "user", "current question");
        verify(memoryService).addMessage("session-1", "assistant", "final answer");

        assertThat(response).isEqualTo("final answer");
        assertThat(historyCaptor.getValue()).hasSize(1);
        assertThat(historyCaptor.getValue().get(0)).isNotInstanceOf(UserMessage.class);
    }
}
