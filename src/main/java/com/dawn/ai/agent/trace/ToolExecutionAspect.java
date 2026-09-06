package com.dawn.ai.agent.trace;

import com.dawn.ai.agent.planning.TaskPlanner;
import com.dawn.ai.agent.token.TokenWindowManager;
import com.dawn.ai.agent.tools.ToolOutcome;
import com.dawn.ai.agent.tools.ToolOutcomeStatus;
import com.dawn.ai.exception.MaxStepsExceededException;
import com.dawn.ai.sse.ChatStreamEvent;
import com.dawn.ai.sse.StreamSinkHolder;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Intercepts every tool invocation in the agent tools package and records it as an AgentStep.
 *
 * The pointcut covers all current and future tools under com.dawn.ai.agent.tools,
 * so new tools are automatically traced without any modification.
 *
 * Also exports per-tool metrics to Prometheus:
 *   - ai.tool.duration{tool, status, outcome}  — execution time histogram
 *   - ai.tool.calls.total{tool, status, outcome} — call count
 *   - ai.tool.retries.total{tool} — safe automatic retry count
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

    @Value("${app.ai.react.max-tool-retries:1}")
    private int maxToolRetries;

    @Value("${app.ai.react.tool-retry-backoff-ms:200}")
    private long retryBackoffMs;

    @Around("execution(* com.dawn.ai.agent.tools..*.apply(..))")
    public Object captureStep(ProceedingJoinPoint pjp) throws Throwable {
        String toolName = pjp.getTarget().getClass().getSimpleName();
        Object input = pjp.getArgs()[0];
        long start = System.currentTimeMillis();
        int stepNum;
        try {
            stepNum = StepCollector.getAndIncreaseStepNumber();
        } catch (MaxStepsExceededException exception) {
            recordMetrics(toolName, ToolOutcomeStatus.REFUSED, System.currentTimeMillis() - start);
            throw exception;
        }

        Object result;
        try {
            result = executeWithRetry(pjp, toolName);
        } catch (Throwable t) {
            long durationMs = System.currentTimeMillis() - start;
            ToolOutcomeStatus outcome = classifyException(t);
            String output = summarizeException(t);

            recordFailureStep(stepNum, toolName, input, output, durationMs, outcome);
            String recoveryGuidance = t instanceof Exception ? maybeTriggerRePlan(outcome) : null;
            if (recoveryGuidance != null) {
                StepCollector.setPendingRecoveryGuidance(recoveryGuidance);
            }

            log.error("[ReAct] Tool={} failed after {}ms: {}", toolName, durationMs, t.getMessage(), t);
            recordMetrics(toolName, outcome, durationMs);
            throw t;
        }

        long durationMs = System.currentTimeMillis() - start;

        // 保留现有 String 返回值截断行为；结构化输出预算不属于本次改动范围。
        if (result instanceof String text) {
            String truncated = tokenWindowManager.truncateToolOutput(text);
            if (truncated.length() < text.length()) {
                log.info("[ToolExecutionAspect] Tool output truncated from {} to {} chars (tool={})",
                        text.length(), truncated.length(), toolName);
            }
            result = truncated;
        }

        ToolOutcomeStatus outcome = classifyResult(result);
        List<AgentStep> subSteps = (result instanceof SubStepProvider provider)
                ? provider.getSubSteps()
                : List.of();
        String output = result == null ? "" : String.valueOf(result);

        StepCollector.record(new AgentStep(
                stepNum,
                toolName,
                input,
                output,
                durationMs,
                outcome.stepStatus(),
                subSteps));

        log.debug("[ReAct] Step {} | tool={} | outcome={} | input={} | output={} | {}ms | subSteps={}",
                stepNum, toolName, outcome, input, result, durationMs, subSteps.size());

        String recoveryGuidance = maybeTriggerRePlan(outcome);
        recordMetrics(toolName, outcome, durationMs);

        if (recoveryGuidance != null) {
            return recoveryGuidance + "\n---\n原始工具输出：\n" + output;
        }
        return result;
    }

    private Object executeWithRetry(ProceedingJoinPoint pjp, String toolName) throws Throwable {
        Object result = pjp.proceed();
        int retry = 0;
        while (retry < Math.max(0, maxToolRetries)
                && result instanceof ToolOutcome outcome
                && outcome.outcomeStatus() == ToolOutcomeStatus.RETRYABLE_FAILURE
                && outcome.safeToRetry()) {
            retry++;
            meterRegistry.counter("ai.tool.retries.total", "tool", toolName).increment();
            log.info("[ReAct] Retrying safe tool={} after retryable failure, attempt={}", toolName, retry + 1);
            if (!waitBeforeRetry(retry)) {
                break;
            }
            result = pjp.proceed();
        }
        return result;
    }

    private boolean waitBeforeRetry(int retry) {
        if (retryBackoffMs <= 0) {
            return true;
        }
        long delayMs = retryBackoffMs * (1L << Math.min(retry - 1, 10));
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("[ReAct] Tool retry interrupted during backoff");
            return false;
        }
    }

    private ToolOutcomeStatus classifyResult(Object result) {
        if (result instanceof ToolOutcome outcome) {
            return outcome.outcomeStatus();
        }
        if (result == null || (result instanceof String text && text.isBlank())) {
            return ToolOutcomeStatus.EMPTY;
        }
        return ToolOutcomeStatus.SUCCESS;
    }

    private ToolOutcomeStatus classifyException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof MaxStepsExceededException) {
                return ToolOutcomeStatus.REFUSED;
            }
            if (current instanceof HttpStatusCodeException httpException) {
                int status = httpException.getStatusCode().value();
                return status == 429 || status >= 500
                        ? ToolOutcomeStatus.RETRYABLE_FAILURE
                        : ToolOutcomeStatus.PERMANENT_FAILURE;
            }
            if (current instanceof TimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof ResourceAccessException
                    || current instanceof TransientDataAccessException
                    || current instanceof IOException) {
                return ToolOutcomeStatus.RETRYABLE_FAILURE;
            }
            current = current.getCause() != current ? current.getCause() : null;
        }
        return ToolOutcomeStatus.PERMANENT_FAILURE;
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String summarizeException(Throwable throwable) {
        Throwable root = rootCause(throwable);
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }
        String summary = root.getClass().getSimpleName() + ": " + message;
        return summary.length() <= 500 ? summary : summary.substring(0, 500) + "…";
    }

    private void recordFailureStep(int stepNum, String toolName, Object input, String output,
                                   long durationMs, ToolOutcomeStatus outcome) {
        try {
            StepCollector.record(new AgentStep(
                    stepNum,
                    toolName,
                    input,
                    output,
                    durationMs,
                    outcome.stepStatus(),
                    List.of()));
        } catch (RuntimeException traceException) {
            log.error("[ReAct] Failed to record failed tool step, tool={}: {}",
                    toolName, traceException.getMessage(), traceException);
        }
    }

    private String maybeTriggerRePlan(ToolOutcomeStatus outcome) {
        if (!outcome.requiresRecovery()) {
            StepCollector.resetConsecutiveRecoverySignals();
            return null;
        }

        int consecutive = StepCollector.incrementConsecutiveRecoverySignals();
        if (consecutive < Math.max(1, rePlanThreshold) || StepCollector.isRePlanTriggered()) {
            return null;
        }

        String userMessage = StepCollector.getUserMessage();
        Set<String> toolDescriptions = StepCollector.getToolDescriptions();
        if (userMessage == null || userMessage.isBlank()
                || toolDescriptions == null || toolDescriptions.isEmpty()) {
            return null;
        }

        // 先标记已尝试，避免重规划自身失败后每个后续工具结果都再次消耗一次 LLM 调用。
        StepCollector.markRePlanTriggered();
        List<AgentStep> completedSteps = StepCollector.collect();
        String rePlanGuidance = taskPlanner.rePlan(userMessage, completedSteps, toolDescriptions);
        if (rePlanGuidance == null || rePlanGuidance.isBlank()) {
            return null;
        }

        Consumer<ChatStreamEvent> sink = StreamSinkHolder.get();
        if (sink != null) {
            sink.accept(ChatStreamEvent.replan(rePlanGuidance));
        }

        log.info("[ToolExecutionAspect] Re-plan triggered after {} consecutive recovery signals, outcome={}",
                consecutive, outcome);
        return "【执行计划调整】连续 " + consecutive + " 次工具调用未获得有效结果"
                + "（最近结果：" + outcome.stepStatus() + "）。系统建议调整策略：\n"
                + rePlanGuidance
                + "\n\n请按照上述调整后的策略继续执行。";
    }

    private void recordMetrics(String toolName, ToolOutcomeStatus outcome, long durationMs) {
        String status = outcome.executionSucceeded() ? "success" : "error";
        meterRegistry.timer("ai.tool.duration",
                        "tool", toolName,
                        "status", status,
                        "outcome", outcome.stepStatus())
                .record(durationMs, TimeUnit.MILLISECONDS);
        meterRegistry.counter("ai.tool.calls.total",
                        "tool", toolName,
                        "status", status,
                        "outcome", outcome.stepStatus())
                .increment();
    }
}
