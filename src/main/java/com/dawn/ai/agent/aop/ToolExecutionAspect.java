package com.dawn.ai.agent.aop;

import com.dawn.ai.agent.AgentStep;
import com.dawn.ai.agent.StepCollector;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

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

    @Around("execution(* com.dawn.ai.agent.tools.*.apply(..))")
    public Object captureStep(ProceedingJoinPoint pjp) throws Throwable {
        String toolName = pjp.getTarget().getClass().getSimpleName();
        Object input = pjp.getArgs()[0];
        long start = System.currentTimeMillis();
        String status = "success";

        try {
            Object result = pjp.proceed();
            long durationMs = System.currentTimeMillis() - start;
            int stepNum = StepCollector.nextStepNumber();

            StepCollector.record(new AgentStep(stepNum, toolName, input, result.toString(), durationMs));

            log.debug("[ReAct] Step {} | tool={} | input={} | output={} | {}ms",
                    stepNum, toolName, input, result, durationMs);

            recordMetrics(toolName, status, durationMs);
            return result;

        } catch (Throwable t) {
            status = "error";
            long durationMs = System.currentTimeMillis() - start;
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
