package com.dawn.ai.agent.hook;

import com.dawn.ai.agent.AgentStep;
import com.dawn.ai.exception.MaxStepsExceededException;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Replaces ToolExecutionAspect + StepCollector step tracking.
 *
 * One instance per request — do NOT register as a Spring singleton bean.
 * Created fresh in AgentOrchestrator.doChat() for each conversation turn.
 *
 * Enforces maxSteps hard limit via PreActingEvent (before tool execution).
 * Records per-tool Micrometer metrics via PostActingEvent (after execution).
 */
@Slf4j
public class StepTraceHook implements Hook {

    private final int maxSteps;
    private final MeterRegistry meterRegistry;
    private final List<AgentStep> steps = new ArrayList<>();

    private volatile String pendingToolName;
    private volatile String pendingInput;
    private volatile long pendingStart;

    public StepTraceHook(int maxSteps, MeterRegistry meterRegistry) {
        this.maxSteps = maxSteps;
        this.meterRegistry = meterRegistry;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent preActingEvent) {
            if (steps.size() >= maxSteps) {
                log.error("[StepTraceHook] maxSteps={} exceeded at tool: {}",
                        maxSteps, preActingEvent.getToolUse().getName());
                return Mono.error(new MaxStepsExceededException(
                        "Max steps (" + maxSteps + ") exceeded"));
            }
            this.pendingToolName = preActingEvent.getToolUse().getName();
            this.pendingInput = preActingEvent.getToolUse().getInput().toString();
            this.pendingStart = System.currentTimeMillis();
            log.debug("[StepTraceHook] Pre-acting: tool={} input={}", pendingToolName, pendingInput);
            return Mono.just(event);
        }

        if (event instanceof PostActingEvent postActingEvent) {
            long durationMs = System.currentTimeMillis() - pendingStart;
            ToolResultBlock result = postActingEvent.getToolResult();
            String output = extractText(result);

            AgentStep step = new AgentStep(
                    steps.size() + 1,
                    pendingToolName,
                    pendingInput,
                    output,
                    durationMs
            );
            steps.add(step);

            log.debug("[ReAct] Step {} | tool={} | input={} | output={} | {}ms",
                    step.stepNumber(), pendingToolName, pendingInput, output, durationMs);

            recordMetrics(pendingToolName, "success", durationMs);
            return Mono.just(event);
        }

        return Mono.just(event);
    }

    private String extractText(ToolResultBlock result) {
        if (result == null || result.getOutput() == null) {
            return "";
        }
        return result.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .collect(Collectors.joining("\n"));
    }

    private void recordMetrics(String toolName, String status, long durationMs) {
        meterRegistry.timer("ai.tool.duration", "tool", toolName, "status", status)
                .record(durationMs, TimeUnit.MILLISECONDS);
        meterRegistry.counter("ai.tool.calls.total", "tool", toolName, "status", status)
                .increment();
    }

    public List<AgentStep> getSteps() {
        return List.copyOf(steps);
    }

    public void clear() {
        steps.clear();
        pendingToolName = null;
        pendingInput = null;
        pendingStart = 0;
    }
}
