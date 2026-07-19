package com.dawn.ai.agent.orchestration;

import com.dawn.ai.agent.planning.TaskPlanner;
import com.dawn.ai.agent.registry.ToolRegistry;
import com.dawn.ai.agent.skill.SkillRegistry;
import com.dawn.ai.agent.subagent.SubAgentRegistry;
import com.dawn.ai.agent.token.TokenWindowManager;
import com.dawn.ai.agent.tools.KnowledgeSearchTool;
import com.dawn.ai.exception.PlanGenerationException;
import com.dawn.ai.service.MemoryService;
import com.dawn.ai.memory.UserProfileService;
import com.dawn.ai.sse.ChatStreamEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorTest {

    private AgentOrchestrator agentOrchestrator;

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
        @Mock private ChatClient.StreamResponseSpec streamResponseSpec;
    @Mock private MemoryService memoryService;
    @Mock private com.dawn.ai.memory.MemoryManager memoryManager;
    @Mock private TaskPlanner taskPlanner;
    @Mock private ToolRegistry toolRegistry;
    @Mock private UserProfileService userProfileService;
    @Mock private SkillRegistry skillRegistry;
    @Mock private SubAgentRegistry subAgentRegistry;
    @Mock private TokenWindowManager tokenWindowManager;
        @Mock private KnowledgeSearchTool knowledgeSearchTool;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(toolRegistry.getNames()).thenReturn(new String[]{"weatherTool", "calculatorTool"});
        when(toolRegistry.getDescriptions()).thenReturn(Map.of(
                "weatherTool", "查询天气",
                "calculatorTool", "数学计算"
        ));
        when(taskPlanner.plan(anyString(), any(), any())).thenReturn(TaskPlanner.PlannerResult.empty());
        when(subAgentRegistry.isEmpty()).thenReturn(true);

        agentOrchestrator = new AgentOrchestrator(
                chatClient,
                memoryService,
                memoryManager,
                taskPlanner,
                toolRegistry,
                new SimpleMeterRegistry(),
                userProfileService,
                skillRegistry,
                subAgentRegistry,
                tokenWindowManager,
                Optional.empty()
        );
        agentOrchestrator.initMetrics();
        // @Value 字段在单元测试（不经 Spring）下不会注入，显式设置固定 userId
        ReflectionTestUtils.setField(agentOrchestrator, "defaultUserId", "local-user");
                ReflectionTestUtils.setField(agentOrchestrator, "model", "test-model");
    }

    @Test
    void shouldSendCurrentUserMessageOnlyOnce() {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("final answer")))
        );

        when(memoryService.getHistory("session-1"))
                .thenReturn(List.of(Map.of("role", "assistant", "content", "previous reply")));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolNames(any(String[].class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.chatResponse()).thenReturn(Flux.just(chatResponse));

        List<ChatStreamEvent> events = new ArrayList<>();
        agentOrchestrator.streamChat("session-1", "current question", null, events::add, () -> false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> historyCaptor = (ArgumentCaptor<List<Message>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(requestSpec).messages(historyCaptor.capture());
        verify(requestSpec).user("current question");
        verify(memoryService).addMessage("session-1", "local-user", "user", "current question");
        verify(memoryService).addMessage("session-1", "local-user", "assistant", "final answer");

        assertThat(doneData(events).get("answer")).isEqualTo("final answer");
        assertThat(historyCaptor.getValue()).hasSize(1);
        assertThat(historyCaptor.getValue().get(0)).isNotInstanceOf(UserMessage.class);
    }

    @Test
    void shouldPreSearchTopicKnowledgeBeforeStreamingModel() {
        agentOrchestrator = new AgentOrchestrator(
                chatClient,
                memoryService,
                memoryManager,
                taskPlanner,
                toolRegistry,
                new SimpleMeterRegistry(),
                userProfileService,
                skillRegistry,
                subAgentRegistry,
                tokenWindowManager,
                Optional.of(knowledgeSearchTool)
        );
        agentOrchestrator.initMetrics();
        ReflectionTestUtils.setField(agentOrchestrator, "defaultUserId", "local-user");
        ReflectionTestUtils.setField(agentOrchestrator, "model", "test-model");

        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("final answer")))
        );

        when(knowledgeSearchTool.apply(any(KnowledgeSearchTool.Request.class)))
                .thenReturn(new KnowledgeSearchTool.Response("[1] Patent US10,234,567 covers image compression.", 1));
        when(memoryService.getHistory("session-topic")).thenReturn(Collections.emptyList());
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolNames(any(String[].class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.chatResponse()).thenReturn(Flux.just(chatResponse));

        List<ChatStreamEvent> events = new ArrayList<>();
        agentOrchestrator.streamChat("session-topic", "What does the patent cover?", "eval-topic", events::add, () -> false);

        ArgumentCaptor<KnowledgeSearchTool.Request> requestCaptor = ArgumentCaptor.forClass(KnowledgeSearchTool.Request.class);
        verify(knowledgeSearchTool).apply(requestCaptor.capture());
        assertThat(requestCaptor.getValue().topicId()).isEqualTo("eval-topic");
        assertThat(requestCaptor.getValue().query()).isEqualTo("What does the patent cover?");

        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(systemCaptor.capture());
        assertThat(systemCaptor.getValue())
                .contains("【预检索结果】")
                .contains("docsFound=1")
                .contains("Patent US10,234,567 covers image compression.");
        assertThat(doneData(events).get("answer")).isEqualTo("final answer");
    }

    @Test
    void shouldFallbackWhenPlannerGenerationFails() {
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("final answer")))
        );

        when(taskPlanner.plan(anyString(), any(), any()))
                .thenThrow(new PlanGenerationException("Planner returned invalid structured output."));
        when(memoryService.getHistory("session-2")).thenReturn(Collections.emptyList());
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolNames(any(String[].class))).thenReturn(requestSpec);
                when(requestSpec.stream()).thenReturn(streamResponseSpec);
                when(streamResponseSpec.chatResponse()).thenReturn(Flux.just(chatResponse));

                List<ChatStreamEvent> events = new ArrayList<>();
                agentOrchestrator.streamChat("session-2", "current question", null, events::add, () -> false);

                assertThat(doneData(events).get("answer")).isEqualTo("final answer");
                assertThat(doneData(events).get("planSummary")).isEqualTo("");
        verify(chatClient).prompt();
        verify(requestSpec, never()).system(org.mockito.ArgumentMatchers.contains("【执行计划】"));
        verify(memoryService).addMessage("session-2", "local-user", "assistant", "final answer");
    }

        @SuppressWarnings("unchecked")
        private Map<String, Object> doneData(List<ChatStreamEvent> events) {
                return events.stream()
                                .filter(event -> "done".equals(event.getEvent()))
                                .map(event -> (Map<String, Object>) event.getData())
                                .findFirst()
                                .orElseThrow();
        }
}
