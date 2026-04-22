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
        } finally {
            // Always clean up the ThreadLocal regardless of which reasoning extraction path was taken.
            // Without this, the captured response body from source-1/2 early-exit paths would linger
            // and could be misread by the next request that falls through to the fallback path.
            AiSyncResponseCapture.clear();
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

    /**
     * Extracts reasoning/thinking content from the planner's ChatResponse.
     *
     * Tries three sources in priority order:
     *
     * --- 来源 1: Generation 元数据 ---
     * Spring AI 官方 adapter（如 DeepSeek-R1 专用 adapter）将 reasoning_content 解析后
     * 存入 GenerationMetadata，可通过 chatResponse.getResult().getMetadata() 直接取得。
     * 示例数据（Spring AI 内部结构）：
     *   chatResponse.getResult().getMetadata()
     *     → { "reasoningContent": "用户问的是高血压诊断，需先查知识库..." }
     *
     * --- 来源 2: AssistantMessage 元数据 ---
     * 部分 Spring AI 版本或自定义 MessageConverter 将 reasoning 写在 message 层而非 generation 层。
     * 示例数据（Spring AI 内部结构）：
     *   chatResponse.getResult().getOutput().getMetadata()
     *     → { "reasoningContent": "用户问的是高血压诊断，需先查知识库..." }
     *
     * --- 来源 3: 兜底——解析原始 HTTP 响应 JSON ---
     * 当 Spring AI 未识别 reasoning_content（如通过 OpenAI-compatible 接口接入 DeepSeek 时），
     * 两层 metadata 均为空，此时从 AiSyncResponseCapture 存储的原始响应体中手动提取。
     * 示例数据（OpenAI-compatible 接口返回的原始 JSON）：
     * {
     *   "choices": [{
     *     "message": {
     *       "role": "assistant",
     *       "content": "[{\"step\":1,\"action\":\"knowledgeSearchTool\",...}]",
     *       "reasoning_content": "用户问的是高血压诊断，需先查知识库..."
     *     }
     *   }]
     * }
     */
    private String extractReasoningContent(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null) {
            return null;
        }

        // 来源 1: GenerationMetadata（Spring AI 官方 adapter 解析后存入）
        String fromGeneration = chatResponse.getResult().getMetadata().get("reasoningContent");
        if (fromGeneration != null && !fromGeneration.isBlank()) {
            return fromGeneration;
        }

        var output = chatResponse.getResult().getOutput();
        if (output == null || output.getMetadata() == null) {
            // NOTE: 此处直接返回 null，跳过了兜底路径，即使 AiSyncResponseCapture 中有数据也不会读取
            return null;
        }

        // 来源 2: AssistantMessage metadata（部分版本/converter 写在 message 层）
        Object fromMessage = output.getMetadata().get("reasoningContent");
        if (fromMessage instanceof String reasoning && !reasoning.isBlank()) {
            return reasoning;
        }

        // 来源 3: 兜底，解析 RestClient 拦截器捕获的原始响应 JSON
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
            // NOTE: 仅走兜底路径时清理 ThreadLocal；来源 1/2 早退时不清理，存在残留风险
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
