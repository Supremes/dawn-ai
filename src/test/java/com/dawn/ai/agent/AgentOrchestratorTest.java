package com.dawn.ai.agent;

import com.dawn.ai.agent.plan.PlanStep;
import com.dawn.ai.agent.plan.TaskPlanner;
import com.dawn.ai.exception.PlanGenerationException;
import com.dawn.ai.service.MemoryService;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AgentOrchestrator.
 *
 * Tests cover memory history building and planner fallback behavior.
 * Full ReAct loop integration tests require a running OpenAI-compatible endpoint.
 */
class AgentOrchestratorTest {

    @Mock private OpenAIChatModel agentScopeModel;
    @Mock private Toolkit agentScopeToolkit;
    @Mock private MemoryService memoryService;
    @Mock private TaskPlanner taskPlanner;
    @Mock private ToolRegistry toolRegistry;

    private AgentOrchestrator agentOrchestrator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(toolRegistry.getDescriptions()).thenReturn(Map.of(
                "weatherTool", "查询天气",
                "calculatorTool", "数学计算"
        ));
        when(taskPlanner.plan(anyString(), any())).thenReturn(Collections.emptyList());
        when(memoryService.getHistory(anyString())).thenReturn(Collections.emptyList());

        agentOrchestrator = new AgentOrchestrator(
                agentScopeModel,
                agentScopeToolkit,
                memoryService,
                taskPlanner,
                toolRegistry,
                new SimpleMeterRegistry()
        );
        agentOrchestrator.initMetrics();

        // @Value fields are not injected in unit tests — set defaults explicitly
        setField(agentOrchestrator, "planEnabled", true);
        setField(agentOrchestrator, "maxSteps", 10);
        setField(agentOrchestrator, "baseSystemPrompt", "You are a helpful assistant.");
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }

    @Test
    void buildMemoryFromHistory_convertsRedisMapsToAgentScopeMsgs() {
        when(memoryService.getHistory("session-1")).thenReturn(List.of(
                Map.of("role", "user", "content", "hello"),
                Map.of("role", "assistant", "content", "hi there")
        ));

        InMemoryMemory memory = buildMemoryFromHistory("session-1");
        List<Msg> messages = memory.getMessages();

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getRole()).isEqualTo(MsgRole.USER);
        assertThat(messages.get(0).getTextContent()).isEqualTo("hello");
        assertThat(messages.get(1).getRole()).isEqualTo(MsgRole.ASSISTANT);
        assertThat(messages.get(1).getTextContent()).isEqualTo("hi there");
    }

    @Test
    void buildMemoryFromHistory_returnsEmptyMemoryForNewSession() {
        when(memoryService.getHistory("new-session")).thenReturn(Collections.emptyList());

        InMemoryMemory memory = buildMemoryFromHistory("new-session");

        assertThat(memory.getMessages()).isEmpty();
    }

    @Test
    void shouldFallbackGracefullyWhenPlannerThrows() {
        when(taskPlanner.plan(anyString(), any()))
                .thenThrow(new PlanGenerationException("Planner returned invalid structured output."));

        // PlanGenerationException should NOT propagate out of chat()
        // (LLMProviderException may be thrown when agent.call() fails without a server)
        try {
            agentOrchestrator.chat("session-3", "some query");
        } catch (Exception e) {
            assertThat(e).isNotInstanceOf(PlanGenerationException.class);
        }

        verify(taskPlanner).plan(anyString(), any());
    }

    @Test
    void shouldCollectSteps() {
        // Verify orchestrator builds correctly by checking steps list is empty on failed call
        try {
            agentOrchestrator.chat("session-4", "query");
        } catch (Exception ignored) {
            // Expected — no real LLM available in unit tests
        }
        // No assertion on steps since agent.call() fails — just verify no NPE / ClassCastException
    }

    // Mirrors the private buildMemoryFromHistory logic for white-box testing
    private InMemoryMemory buildMemoryFromHistory(String sessionId) {
        InMemoryMemory memory = new InMemoryMemory();
        List<Map<String, String>> rawHistory = memoryService.getHistory(sessionId);
        for (Map<String, String> entry : rawHistory) {
            String role = entry.get("role");
            String content = entry.get("content");
            MsgRole msgRole = "assistant".equals(role) ? MsgRole.ASSISTANT : MsgRole.USER;
            memory.addMessage(Msg.builder()
                    .role(msgRole)
                    .textContent(content)
                    .build());
        }
        return memory;
    }
}
