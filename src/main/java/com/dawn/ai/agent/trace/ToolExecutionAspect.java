package com.dawn.ai.agent.trace;

import com.dawn.ai.agent.planning.PlanStep;
import com.dawn.ai.agent.planning.TaskPlanner;
import com.dawn.ai.agent.token.TokenWindowManager;
import com.dawn.ai.sse.ChatStreamEvent;
import com.dawn.ai.sse.StreamSinkHolder;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
     * Intercepts every tool invocation in the agent tools package and records it as an AgentStep.
 *
 * The pointcut covers all current and future tools under com.dawn.ai.agent.tools,
 * so new tools are automatically traced without any modification.
 *
 * Also exports per-tool metrics to Prometheus:
 *   - ai.tool.duration{tool, status}  — execution time histogram
 *   - ai.tool.calls.total{tool, status} — call count (success / error)
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ToolExecutionAspect {

    private final MeterRegistry meterRegistry;
    private final TokenWindowManager tokenWindowManager;
    private final TaskPlanner taskPlanner;

    @Value("${app.ai.react.replan-threshold:2}")
    private int rePlanThreshold;

    @Around("execution(* com.dawn.ai.agent.tools.*.apply(..))")
    public Object captureStep(ProceedingJoinPoint pjp) throws Throwable {
        String toolName = pjp.getTarget().getClass().getSimpleName();
        Object input = pjp.getArgs()[0];
        long start = System.currentTimeMillis();
        String status = "success";

        try {
            int stepNum = StepCollector.getAndIncreaseStepNumber();

            Object result = pjp.proceed();
            long durationMs = System.currentTimeMillis() - start;

            // Token-aware truncation of tool output
            if (result instanceof String text) {
                String truncated = tokenWindowManager.truncateToolOutput(text);
                if (truncated.length() < text.length()) {
                    log.info("[ToolExecutionAspect] Tool output truncated from {} to {} chars (tool={})",
                            text.length(), truncated.length(), toolName);
                }
                result = truncated;
            }

            List<AgentStep> subSteps = (result instanceof SubStepProvider provider)
                    ? provider.getSubSteps()
                    : List.of();

            StepCollector.record(new AgentStep(stepNum, toolName, input, result.toString(), durationMs,
                    status, subSteps));

            log.debug("[ReAct] Step {} | tool={} | input={} | output={} | {}ms | subSteps={}",
                    stepNum, toolName, input, result, durationMs, subSteps.size());

            // ── Plan B: detect consecutive empty/failed results and trigger re-planning ──
            String outputStr = (result instanceof String s) ? s : String.valueOf(result);
            boolean isEmpty = outputStr == null || outputStr.isBlank()
                    || outputStr.contains("docsFound=0")
                    || outputStr.contains("未找到")
                    || outputStr.contains("No results");

            if (isEmpty) {
                int consecutive = StepCollector.incrementConsecutiveEmpty();
                if (consecutive >= rePlanThreshold && !StepCollector.isRePlanTriggered()) {
                    List<PlanStep> currentPlan = StepCollector.getCurrentPlan();
                    if (currentPlan != null && !currentPlan.isEmpty()) {
                        String userMsg = StepCollector.getUserMessage();
                        Set<String> toolDescs = StepCollector.getToolDescriptions();
                        List<AgentStep> completedSteps = StepCollector.collect();

                        String rePlanGuidance = taskPlanner.rePlan(userMsg, completedSteps, toolDescs);
                        if (rePlanGuidance != null && !rePlanGuidance.isBlank()) {
                            StepCollector.markRePlanTriggered();

                            Consumer<ChatStreamEvent> sink = StreamSinkHolder.get();
                            if (sink != null) {
                                sink.accept(ChatStreamEvent.replan(rePlanGuidance));
                            }

                            result = "【执行计划调整】连续 " + consecutive + " 次工具调用未获得有效结果。系统建议调整策略：\n"
                                    + rePlanGuidance
                                    + "\n\n请按照上述调整后的策略继续执行。\n---\n原始工具输出：\n" + outputStr;

                            log.info("[ToolExecutionAspect] Re-plan triggered after {} consecutive empty results", consecutive);
                        }
                    }
                }
            } else {
                StepCollector.resetConsecutiveEmpty();
            }

            recordMetrics(toolName, status, durationMs);
            return result;

        } catch (Throwable t) {
            status = "error";
            long durationMs = System.currentTimeMillis() - start;
            log.error("[ReAct] Tool={} failed after {}ms: {}", toolName, durationMs, t.getMessage(), t);
            recordMetrics(toolName, status, durationMs);
            throw t;
        }
    }

    private void recordMetrics(String toolName, String status, long durationMs) {
        meterRegistry.timer("ai.tool.duration", "tool", toolName, "status", status)
                .record(durationMs, TimeUnit.MILLISECONDS);
        meterRegistry.counter("ai.tool.calls.total", "tool", toolName, "status", status)
                .increment();
    }
}
