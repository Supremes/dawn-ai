package com.dawn.ai.agent.planning;

import com.dawn.ai.config.AiSyncResponseCapture;
import com.dawn.ai.exception.PlanGenerationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates a structured execution plan before the main ReAct loop.
 *
 * The planning call is independent from the conversation history:
 * it needs a low-temperature, global view of the task, not a contextual one.
 *
 * Metrics:
 *   ai.planner.result{status=success}     — plan generated and validated successfully
 *   ai.planner.result{status=parse_error} — structured output could not be parsed or validated
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskPlanner {

    public record PlannerResult(List<PlanStep> steps, String reasoningContent) {
        public static PlannerResult empty() {
            return new PlannerResult(List.of(), null);
        }
    }

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${app.ai.rag.max-calls-per-session:3}")
    private int maxRagCalls;

    private Counter successCounter;
    private Counter parseErrorCounter;

    @PostConstruct
    public void initMetrics() {
        successCounter = Counter.builder("ai.planner.result")
                .description("TaskPlanner outcomes: success vs parse_error")
                .tag("status", "success")
                .register(meterRegistry);
        parseErrorCounter = Counter.builder("ai.planner.result")
                .description("TaskPlanner outcomes: success vs parse_error")
                .tag("status", "parse_error")
                .register(meterRegistry);
    }

    /**
     * Plans the steps required to complete {@code task} given the available tools.
     *
     * @param task             the user's request
     * @param toolDescriptions map of tool name → description
     * @return ordered plan steps
     * @throws PlanGenerationException when the model output is not valid structured planner output
     */
        public PlannerResult plan(String task, Map<String, String> toolDescriptions) {
        try {
            BeanOutputConverter<List<PlanStep>> converter =
                    new BeanOutputConverter<>(new ParameterizedTypeReference<>() {}, objectMapper);

            String prompt = buildPlanPrompt(task, toolDescriptions, converter.getFormat());
            ChatResponse chatResponse = chatClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder().temperature(0.3).build())
                    .call()
                    .chatResponse();

            String raw = chatResponse.getResult().getOutput().getText();
            String reasoningContent = extractReasoningContent(chatResponse);

            List<PlanStep> plan = converter.convert(raw);
            validatePlan(plan, toolDescriptions.keySet());

            log.debug("[TaskPlanner] Generated {} steps for task: {}", plan.size(),
                    task.substring(0, Math.min(50, task.length())));
            successCounter.increment();
            return new PlannerResult(plan, reasoningContent);
        } catch (PlanGenerationException exception) {
            parseErrorCounter.increment();
            throw exception;
        } catch (RuntimeException exception) {
            parseErrorCounter.increment();
            throw new PlanGenerationException("Planner returned invalid structured output.", exception);
        }
    }

    private String buildPlanPrompt(String task,
                                   Map<String, String> toolDescriptions,
                                   String formatInstructions) {
        String toolList = toolDescriptions.entrySet().stream()
                .map(e -> "- " + e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));

        return """
                你是一个任务规划助手。请分析用户的任务，并生成一个 1-5 步的执行计划。

                可用工具：
                %s

                业务约束：
                - action 只能从上方可用工具中选择，最后一步固定为 "finish"
                - reason 使用中文，简短说明为什么要执行该步骤
                - 若单次检索信息不足，可多次调用 knowledgeSearchTool 从不同角度补充，
                  直到信息充分再生成最终答案。每次请求最多检索 %d 次。

                用户任务：%s

                %s
                """.formatted(toolList, maxRagCalls, task, formatInstructions);
    }

    private String extractReasoningContent(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null) {
            return null;
        }

        String fromGeneration = chatResponse.getResult().getMetadata().get("reasoningContent");
        if (fromGeneration != null && !fromGeneration.isBlank()) {
            return fromGeneration;
        }

        var output = chatResponse.getResult().getOutput();
        if (output == null || output.getMetadata() == null) {
            return null;
        }

        Object fromMessage = output.getMetadata().get("reasoningContent");
        if (fromMessage instanceof String reasoning && !reasoning.isBlank()) {
            return reasoning;
        }

        return extractReasoningFromCapturedResponse();
    }

    private String extractReasoningFromCapturedResponse() {
        String rawResponse = AiSyncResponseCapture.get();
        if (rawResponse == null || rawResponse.isBlank()) {
            return null;
        }

        try {
            var root = objectMapper.readTree(rawResponse);
            var choice = root.path("choices").path(0);
            String reasoningContent = choice.path("message").path("reasoning_content").asText("");
            return reasoningContent.isBlank() ? null : reasoningContent;
        } catch (Exception ignored) {
            return null;
        } finally {
            AiSyncResponseCapture.clear();
        }
    }

    private void validatePlan(List<PlanStep> plan, Set<String> toolNames) {
        if (plan == null || plan.isEmpty()) {
            throw new PlanGenerationException("Planner returned an empty plan.");
        }

        for (PlanStep step : plan) {
            if (!"finish".equals(step.action()) && !toolNames.contains(step.action())) {
                throw new PlanGenerationException("Planner returned an unknown tool action: " + step.action());
            }
        }

        PlanStep lastStep = plan.get(plan.size() - 1);
        if (!"finish".equals(lastStep.action())) {
            throw new PlanGenerationException("Planner plan must end with finish.");
        }
    }
}
