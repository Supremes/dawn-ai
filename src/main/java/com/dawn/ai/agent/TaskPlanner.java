package com.dawn.ai.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates a structured execution plan before the main ReAct loop.
 *
 * The planning call is completely independent from the conversation history:
 * it needs a low-temperature, global view of the task, not a contextual one.
 * Failure is non-fatal — the orchestrator degrades gracefully to no-plan mode.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskPlanner {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    /**
     * Plans the steps required to complete {@code task} given the available tools.
     *
     * @param task                 the user's request
     * @param toolDescriptions     map of tool name → description
     * @return ordered plan steps, or an empty list on any failure
     */
    public List<PlanStep> plan(String task, Map<String, String> toolDescriptions) {
        String prompt = buildPlanPrompt(task, toolDescriptions);
        try {
            String raw = chatClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder().temperature(0.3).build())
                    .call()
                    .content();

            String json = extractJson(raw);
            List<Map<String, Object>> rawSteps = objectMapper.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() {});

            List<PlanStep> plan = rawSteps.stream()
                    .map(m -> new PlanStep(
                            ((Number) m.get("step")).intValue(),
                            String.valueOf(m.get("action")),
                            String.valueOf(m.get("reason"))))
                    .collect(Collectors.toList());

            log.debug("[TaskPlanner] Generated {} steps for task: {}", plan.size(),
                    task.substring(0, Math.min(50, task.length())));
            return plan;

        } catch (Exception e) {
            log.warn("[TaskPlanner] Planning failed, falling back to no-plan mode: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String buildPlanPrompt(String task, Map<String, String> toolDescriptions) {
        String toolList = toolDescriptions.entrySet().stream()
                .map(e -> "- " + e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));

        return """
                你是一个任务规划助手。请分析用户的任务，生成一个 2-5 步的执行计划。
                
                可用工具：
                %s
                
                严格以 JSON 数组格式返回，每项包含：
                - step: 步骤编号（从 1 开始）
                - action: 工具名称（从上方可用工具中选择），最后一步固定为 "finish"
                - reason: 使用该工具的原因（中文，简短）
                
                要求：
                - 只返回 JSON 数组，不要其他文字
                - 最后一步必须是 {"step": N, "action": "finish", "reason": "完成任务"}
                
                用户任务：%s
                
                示例格式：
                [{"step":1,"action":"weatherTool","reason":"查询北京当前天气"},{"step":2,"action":"finish","reason":"完成任务"}]
                """.formatted(toolList, task);
    }

    /** Extracts the JSON array from LLM output that may contain markdown fences. */
    private String extractJson(String raw) {
        String trimmed = raw.trim();
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}
