package com.dawn.ai.agent.planning;

import com.dawn.ai.exception.PlanGenerationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

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
      when(callResponseSpec.chatResponse()).thenReturn(chatResponse("""
        [
          {"step": 1, "action": "weatherTool", "reason": "先查询天气"}
        ]
        """));

      var plan = taskPlanner.plan("帮我看天气", Map.of("weatherTool", "查询天气"), "");

      assertThat(plan.steps()).hasSize(1);
      assertThat(plan.steps().get(0).step()).isEqualTo(1);
      assertThat(plan.steps().get(0).action()).isEqualTo("weatherTool");
      assertThat(plan.reasoningContent()).isNull();
      assertThat(plan.generated()).isTrue();

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).doesNotContain("严格以 JSON 数组格式返回");
        assertThat(promptCaptor.getValue()).contains("直接输出空数组 []");
    }

    @Test
    void shouldAllowEmptyPlanForDirectAnswer() {
      when(callResponseSpec.chatResponse()).thenReturn(chatResponse("[]"));

      var plan = taskPlanner.plan("解释依赖注入", Map.of("weatherTool", "查询天气"), "");

      assertThat(plan.steps()).isEmpty();
      assertThat(plan.generated()).isTrue();
    }

    @Test
    void shouldThrowWhenPlannerOutputIsNotStructuredJson() {
      when(callResponseSpec.chatResponse()).thenReturn(chatResponse("我建议先查天气，再完成任务。"));

        assertThatThrownBy(() -> taskPlanner.plan("帮我看天气", Map.of("weatherTool", "查询天气"), ""))
                .isInstanceOf(PlanGenerationException.class)
                .hasMessage("Planner returned invalid structured output.");
    }

    @Test
    void shouldThrowWhenRequiredPlanFieldIsMissing() {
      when(callResponseSpec.chatResponse()).thenReturn(chatResponse("""
        [
          {"action": "weatherTool", "reason": "先查询天气"}
        ]
        """));

        assertThatThrownBy(() -> taskPlanner.plan("帮我看天气", Map.of("weatherTool", "查询天气"), ""))
                .isInstanceOf(PlanGenerationException.class)
                .hasMessage("Planner returned invalid structured output.");
    }

        private ChatResponse chatResponse(String content) {
      return new ChatResponse(java.util.List.of(new Generation(new AssistantMessage(content))));
        }
}
