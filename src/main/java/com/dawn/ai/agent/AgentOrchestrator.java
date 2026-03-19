package com.dawn.ai.agent;

import com.dawn.ai.service.MemoryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent Orchestrator — orchestrates the full ReAct loop with planning and step tracing.
 *
 * Flow per request:
 *  1. StepCollector.init()         — reset thread-local state
 *  2. TaskPlanner.plan()           — pre-execution planning via a separate LLM call (optional)
 *  3. Build system prompt          — base prompt + plan summary + max-steps instruction
 *  4. chatClient + .toolNames()    — Spring AI handles the tool-calling loop
 *  5. ToolExecutionAspect (AOP)    — intercepts each tool call, records it automatically
 *  6. StepCollector.collect()      — gather all recorded steps
 *  7. Mark plan steps completed    — correlate plan with actual tool calls
 *  8. Persist to memory            — save turn to Redis
 *  9. StepCollector.clear()        — prevent ThreadLocal memory leak
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final ChatClient chatClient;
    private final MemoryService memoryService;
    private final TaskPlanner taskPlanner;
    private final MeterRegistry meterRegistry;

    @Value("${app.ai.system-prompt:You are a helpful AI assistant.}")
    private String baseSystemPrompt;

    @Value("${app.ai.react.max-steps:10}")
    private int maxSteps;

    @Value("${app.ai.react.plan-enabled:true}")
    private boolean planEnabled;

    public AgentResult chat(String sessionId, String userMessage) {
        return Timer.builder("ai.agent.chat.duration")
                .tag("session", "anonymous")
                .register(meterRegistry)
                .record(() -> doChat(sessionId, userMessage));
    }

    private AgentResult doChat(String sessionId, String userMessage) {
        // ① Reset per-request step tracking
        StepCollector.init();
        try {
            // ② Optional pre-execution planning
            List<PlanStep> plan;
            if (planEnabled) {
                plan = taskPlanner.plan(userMessage, getToolDescriptions());
            } else {
                plan = Collections.emptyList();
            }

            // ③ Compose system prompt: base + plan summary + max-steps hint
            String systemPrompt = baseSystemPrompt
                    + formatPlan(plan)
                    + String.format("%n请在回复中简短说明每次工具调用的原因。最多调用工具 %d 次。", maxSteps);

            // ④ Load conversation history
            List<Message> history = buildHistory(sessionId);

            // ⑤ LLM call — AOP intercepts tool invocations inside this call
            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .messages(history)
                    .user(userMessage)
                    .toolNames("weatherTool", "calculatorTool")
                    .call()
                    .content();

            // ⑥ Collect the steps recorded by ToolExecutionAspect
            List<AgentStep> steps = StepCollector.collect();

            // ⑦ Mark which plan steps were fulfilled
            markPlanStepsCompleted(plan, steps);

            // ⑧ Persist turn to memory
            memoryService.addMessage(sessionId, "user", userMessage);
            memoryService.addMessage(sessionId, "assistant", response);

            log.info("[AgentOrchestrator] session={}, planSteps={}, toolCalls={}, userMsg={}",
                    sessionId, plan.size(), steps.size(),
                    userMessage.substring(0, Math.min(50, userMessage.length())));

            return new AgentResult(response, steps, plan);

        } finally {
            // ⑨ Always clean up to prevent ThreadLocal memory leaks
            StepCollector.clear();
        }
    }

    private List<Message> buildHistory(String sessionId) {
        List<Map<String, String>> rawHistory = memoryService.getHistory(sessionId);
        List<Message> messages = new ArrayList<>();
        for (Map<String, String> entry : rawHistory) {
            String role = entry.get("role");
            String content = entry.get("content");
            if ("user".equals(role)) {
                messages.add(new UserMessage(content));
            } else if ("assistant".equals(role)) {
                messages.add(new AssistantMessage(content));
            }
        }
        return messages;
    }

    private Map<String, String> getToolDescriptions() {
        Map<String, String> tools = new LinkedHashMap<>();
        tools.put("weatherTool", "查询指定城市的当前天气信息（温度、天气状况）");
        tools.put("calculatorTool", "执行数学计算，支持加减乘除和括号表达式");
        return tools;
    }

    /** Formats the plan into a human-readable block for the system prompt. */
    private String formatPlan(List<PlanStep> plan) {
        if (plan.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n【执行计划】\n");
        for (PlanStep step : plan) {
            sb.append(step.getStepNumber())
              .append(". [").append(step.getAction()).append("] ")
              .append(step.getReason()).append("\n");
        }
        return sb.toString();
    }

    /** Marks plan steps as completed based on which tools were actually invoked. */
    private void markPlanStepsCompleted(List<PlanStep> plan, List<AgentStep> steps) {
        Set<String> usedTools = steps.stream()
                .map(AgentStep::toolName)
                .collect(Collectors.toSet());
        for (PlanStep planStep : plan) {
            if (usedTools.contains(planStep.getAction()) || "finish".equals(planStep.getAction())) {
                planStep.setCompleted(true);
            }
        }
    }
}
