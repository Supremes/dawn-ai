package com.dawn.ai.agent;

import com.dawn.ai.agent.plan.TaskPlanner;
import com.dawn.ai.exception.PlanGenerationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskPlannerTest {

    private TaskPlanner taskPlanner;

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        taskPlanner = new TaskPlanner(chatClient, new ObjectMapper(), new SimpleMeterRegistry());
        taskPlanner.initMetrics();
    }

    @Test
    void shouldParsePlanWithBeanOutputConverter() {
        when(callResponseSpec.content()).thenReturn("""
                [
                  {"step": 1, "action": "weatherTool", "reason": "先查询天气"},
                  {"step": 2, "action": "finish", "reason": "完成任务"}
                ]
                """);

        var plan = taskPlanner.plan("帮我看天气", Map.of("weatherTool", "查询天气"));

        assertThat(plan).hasSize(2);
        assertThat(plan.get(0).step()).isEqualTo(1);
        assertThat(plan.get(0).action()).isEqualTo("weatherTool");
        assertThat(plan.get(1).action()).isEqualTo("finish");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).doesNotContain("严格以 JSON 数组格式返回");
    }

    @Test
    void shouldThrowWhenPlannerOutputIsNotStructuredJson() {
        when(callResponseSpec.content()).thenReturn("我建议先查天气，再完成任务。");

        assertThatThrownBy(() -> taskPlanner.plan("帮我看天气", Map.of("weatherTool", "查询天气")))
                .isInstanceOf(PlanGenerationException.class)
                .hasMessage("Planner returned invalid structured output.");
    }

    @Test
    void shouldThrowWhenRequiredPlanFieldIsMissing() {
        when(callResponseSpec.content()).thenReturn("""
                [
                  {"action": "weatherTool", "reason": "先查询天气"},
                  {"step": 2, "action": "finish", "reason": "完成任务"}
                ]
                """);

        assertThatThrownBy(() -> taskPlanner.plan("帮我看天气", Map.of("weatherTool", "查询天气")))
                .isInstanceOf(PlanGenerationException.class)
                .hasMessage("Planner returned invalid structured output.");
    }
}
