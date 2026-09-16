package com.dawn.ai.agent.trace;

import com.dawn.ai.agent.planning.TaskPlanner;
import com.dawn.ai.agent.token.TokenWindowManager;
import com.dawn.ai.agent.tools.ToolOutcome;
import com.dawn.ai.agent.tools.ToolOutcomeStatus;
import com.dawn.ai.exception.MaxStepsExceededException;
import com.dawn.ai.sse.ChatStreamEvent;
import com.dawn.ai.sse.StreamSinkHolder;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.core.exception.AcquirePermissionCancelledException;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 拦截 agent tools 包下的每一次工具调用，并记录为一条 AgentStep。
 *
 * 切点覆盖 com.dawn.ai.agent.tools 下现有及未来的所有工具，
 * 新增工具无需任何改动即可自动被追踪。
 *
 * 弹性能力（resilience4j，通过 application.yml 中的 {@code resilience4j.*} 配置）：
 *   - Retry：每个工具一个从 {@link RetryRegistry} 取得的 resilience4j {@link Retry}
 *     （{@code resilience4j.retry.configs.default} / {@code configs.<ToolName>}）。只重试
 *     状态为 {@code RETRYABLE_FAILURE} 且 {@code safeToRetry()} 的 {@link ToolOutcome} 结果，
 *     外加 {@link BulkheadFullException}（工具体根本没执行）。工具体抛出的异常一律不重试
 *     （BashTool 这类非幂等工具绝不能因异常重跑）；{@link CallNotPermittedException} 快速失败。
 *   - Bulkhead：每个工具一个信号量 {@link Bulkhead}（{@code resilience4j.bulkhead.instances.<ToolName>}，
 *     缺省回落到 {@code configs.default}）。Retry 包在 Bulkhead 外层，所以每次尝试之间许可都会释放。
 *     BulkheadFullException 的同类：调用线程已被中断时（即使还有空闲许可）会抛出
 *     {@link AcquirePermissionCancelledException}。此时工具体同样没执行，但不会重试
 *     （请求正在被拆除），并归类为 PERMANENT_FAILURE。
 *
 * 同时向 Prometheus 导出每个工具的指标：
 *   - ai.tool.duration{tool, status, outcome}  —— 执行耗时直方图
 *   - ai.tool.calls.total{tool, status, outcome} —— 调用次数
 *   - ai.tool.retries.total{tool} —— 安全自动重试次数（来自 Retry 的 onRetry 事件）
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ToolExecutionAspect {

    private final MeterRegistry meterRegistry;
    private final TokenWindowManager tokenWindowManager;
    private final TaskPlanner taskPlanner;
    private final RetryRegistry retryRegistry;
    private final BulkheadRegistry bulkheadRegistry;

    /** 每个工具一个 Retry 实例（onRetry 监听器只注册一次），键为工具类 simpleName。 */
    private final ConcurrentMap<String, Retry> toolRetries = new ConcurrentHashMap<>();

    @Value("${app.ai.react.replan-threshold:2}")
    private int rePlanThreshold;

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
            result = executeWithResilience(pjp, toolName);
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

    /**
     * Retry 在外、Bulkhead 在内：BulkheadFullException（没拿到许可，工具体根本没执行）可以被重试，
     * 且每次尝试之间许可都会被释放。decorateCheckedSupplier 原样抛出工具体的 Throwable，不做包装。
     */
    private Object executeWithResilience(ProceedingJoinPoint pjp, String toolName) throws Throwable {
        Bulkhead bulkhead = toolBulkhead(toolName);
        Retry retry = toolRetries.computeIfAbsent(toolName, this::createToolRetry);
        AtomicReference<Object> lastResult = new AtomicReference<>();
        try {
            return Retry.decorateCheckedSupplier(retry, () -> {
                Object r = bulkhead.executeCheckedSupplier(pjp::proceed);
                lastResult.set(r);
                return r;
            }).get();
        } catch (NullPointerException e) {
            // resilience4j 2.2.0 RetryImpl$ContextImpl.waitIntervalAfterRuntimeException：
            // 结果型重试的 backoff sleep 被中断时会 throw lastRuntimeException.get()（为 null）=> NPE，
            // 且 interrupt 标志已被 Thread.sleep 清掉。这里手动恢复标志并返回最后一次结果（保持旧语义）。
            if (lastResult.get() != null && isRetryBackoffNpe(e)) {
                Thread.currentThread().interrupt();
                log.warn("[ReAct] Tool retry interrupted during backoff, returning last result (tool={})", toolName);
                return lastResult.get();
            }
            throw e;
        }
    }

    /** 工具体自己抛的 NPE 栈顶是工具类；resilience4j 退避中断产生的 NPE 栈顶在 RetryImpl 内部。 */
    private static boolean isRetryBackoffNpe(NullPointerException e) {
        StackTraceElement[] st = e.getStackTrace();
        return st.length > 0
                && st[0].getClassName().startsWith("io.github.resilience4j.retry.internal.RetryImpl");
    }

    /**
     * 优先级：starter 按 {@code instances.<ToolName>} 预建的实例 > {@code configs.<ToolName>} > {@code configs.default}。
     * 注意 {@code bulkhead(name)} 只会用 default 配置，所以 configs.<ToolName> 需要显式查找。
     */
    private Bulkhead toolBulkhead(String toolName) {
        return bulkheadRegistry.find(toolName).orElseGet(() -> bulkheadRegistry.bulkhead(toolName,
                bulkheadRegistry.getConfiguration(toolName).orElseGet(bulkheadRegistry::getDefaultConfig)));
    }

    private Retry createToolRetry(String toolName) {
        // 优先级：运维在 instances.<ToolName> 里预建的实例（不推荐但容忍）> configs.<ToolName> > configs.default
        RetryConfig base = retryRegistry.find(toolName).map(Retry::getRetryConfig)
                .or(() -> retryRegistry.getConfiguration(toolName))
                .orElseGet(retryRegistry::getDefaultConfig);
        RetryConfig config = RetryConfig.<Object>from(base)
                .retryOnResult(result -> result instanceof ToolOutcome outcome
                        && outcome.outcomeStatus() == ToolOutcomeStatus.RETRYABLE_FAILURE
                        && outcome.safeToRetry())
                // 工具体抛出的异常一律不重试（BashTool 等非幂等工具绝不能因异常重跑）；
                // 唯一例外：BulkheadFullException —— 工具体根本没执行，重试安全。
                // CallNotPermittedException（LLM 熔断打开）显式不重试，快速失败。
                .retryOnException(t -> t instanceof BulkheadFullException)
                .failAfterMaxAttempts(false)
                .build();
        // 先 remove 再 retry(name, config)：用我们的谓词替换掉 starter 预建实例的「任何异常都重试」默认谓词，
        // 同时保证实例仍注册在 registry 里，TaggedRetryMetricsPublisher 才会为它绑定 resilience4j_retry_calls。
        if (retryRegistry.find(toolName).isPresent()) {
            retryRegistry.remove(toolName);
        }
        Retry retry = retryRegistry.retry(toolName, config);
        retry.getEventPublisher().onRetry(event -> {
            meterRegistry.counter("ai.tool.retries.total", "tool", toolName).increment();
            log.info("[ReAct] Retrying safe tool={} after retryable failure, attempt={} (wait={}ms)",
                    toolName, event.getNumberOfRetryAttempts() + 1, event.getWaitInterval().toMillis());
        });
        return retry;
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
            if (current instanceof CallNotPermittedException) {
                // LLM 熔断打开：快速失败，交给 re-plan
                return ToolOutcomeStatus.PERMANENT_FAILURE;
            }
            if (current instanceof BulkheadFullException) {
                // 并发隔离满：工具体未执行
                return ToolOutcomeStatus.RETRYABLE_FAILURE;
            }
            if (current instanceof AcquirePermissionCancelledException) {
                // 线程在拿 Bulkhead 许可前已被中断（请求取消）：工具体未执行，不重试，直接失败
                return ToolOutcomeStatus.PERMANENT_FAILURE;
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
        if (root instanceof AcquirePermissionCancelledException) {
            return "ToolCallCancelled: 线程在工具执行前已被中断（请求已取消），工具体未执行";
        }
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
