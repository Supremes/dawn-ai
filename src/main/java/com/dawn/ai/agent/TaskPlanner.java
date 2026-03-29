package com.dawn.ai.agent;

import com.dawn.ai.exception.PlanGenerationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
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

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private Counter successCounter;
    private Counter parseErrorCounter;

    @PostConstruct
    void initMetrics() {
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
    public List<PlanStep> plan(String task, Map<String, String> toolDescriptions) {
        try {
        BeanOutputConverter<List<PlanStep>> converter =
                new BeanOutputConverter<>(new ParameterizedTypeReference<>() {}, objectMapper);

        String prompt = buildPlanPrompt(task, toolDescriptions, converter.getFormat());
        String raw = chatClient.prompt()
                .user(prompt)
                .options(OpenAiChatOptions.builder().temperature(0.3).build())
                .call()
                .content();


            List<PlanStep> plan = converter.convert(raw);
            validatePlan(plan, toolDescriptions.keySet());

            log.debug("[TaskPlanner] Generated {} steps for task: {}", plan.size(),
                    task.substring(0, Math.min(50, task.length())));
            successCounter.increment();
            return plan;
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
                你是一个任务规划助手。请分析用户的任务，并生成一个 2-5 步的执行计划。

                可用工具：
                %s

                业务约束：
                - action 只能从上方可用工具中选择，最后一步固定为 "finish"
                - reason 使用中文，简短说明为什么要执行该步骤

                用户任务：%s

                %s
                """.formatted(toolList, task, formatInstructions);
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
