package com.dawn.ai.agent.trace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dawn.ai.agent.planning.TaskPlanner;
import com.dawn.ai.agent.token.TokenWindowManager;
import com.dawn.ai.agent.tools.ToolOutcome;
import com.dawn.ai.agent.tools.ToolOutcomeStatus;
import com.dawn.ai.exception.MaxStepsExceededException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.exception.AcquirePermissionCancelledException;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link ToolExecutionAspect} 基于 resilience4j 的 Retry + Bulkhead 语义。
 *
 * <p>直接用 mock 的 {@link ProceedingJoinPoint} 调用切面方法，Registry 全部在测试内构造，
 * 不依赖 Spring 容器 / 数据库 / Redis / 网络。
 */
class ToolExecutionAspectResilienceTest {

    /** 工具类 simpleName 即 Retry/Bulkhead 实例名。 */
    static class WebTool {
    }

    static class BashTool {
    }

    record Outcome(ToolOutcomeStatus outcomeStatus, boolean safeToRetry) implements ToolOutcome {
    }

    private static final Outcome RETRYABLE_SAFE = new Outcome(ToolOutcomeStatus.RETRYABLE_FAILURE, true);
    private static final Outcome RETRYABLE_UNSAFE = new Outcome(ToolOutcomeStatus.RETRYABLE_FAILURE, false);
    private static final Outcome PERMANENT = new Outcome(ToolOutcomeStatus.PERMANENT_FAILURE, true);
    private static final Outcome OK = new Outcome(ToolOutcomeStatus.SUCCESS, true);

    private MeterRegistry meterRegistry;
    private TokenWindowManager tokenWindowManager;
    private TaskPlanner taskPlanner;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        tokenWindowManager = mock(TokenWindowManager.class);
        when(tokenWindowManager.truncateToolOutput(anyString())).thenAnswer(inv -> inv.getArgument(0));
        taskPlanner = mock(TaskPlanner.class);
        StepCollector.init(50);

        logger = (Logger) LoggerFactory.getLogger(ToolExecutionAspect.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        StepCollector.clear();
        logger.detachAppender(appender);
        logger.setLevel(null);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static RetryConfig retryConfig(int maxAttempts) {
        return RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(1))
                .build();
    }

    private static RetryRegistry retryRegistry(int defaultMaxAttempts) {
        return RetryRegistry.of(retryConfig(defaultMaxAttempts));
    }

    private static BulkheadRegistry bulkheadRegistry() {
        return BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(32)
                .maxWaitDuration(Duration.ZERO)
                .build());
    }

    private ToolExecutionAspect aspect(RetryRegistry retryRegistry, BulkheadRegistry bulkheadRegistry) {
        return new ToolExecutionAspect(meterRegistry, tokenWindowManager, taskPlanner, retryRegistry, bulkheadRegistry);
    }

    private static ProceedingJoinPoint joinPoint(Object target) {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getTarget()).thenReturn(target);
        when(pjp.getArgs()).thenReturn(new Object[]{"input"});
        return pjp;
    }

    private double retries(String tool) {
        return meterRegistry.counter("ai.tool.retries.total", "tool", tool).count();
    }

    private double calls(String tool, ToolOutcomeStatus outcome) {
        String status = outcome.executionSucceeded() ? "success" : "error";
        return meterRegistry.counter("ai.tool.calls.total",
                "tool", tool, "status", status, "outcome", outcome.stepStatus()).count();
    }

    private List<String> logMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    // -------------------------------------------------------------------------
    // Retry on result
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("RETRYABLE_FAILURE + safeToRetry：重试一次，计数 ai.tool.retries.total 并打 [ReAct] Retrying 日志")
    void retryableSafeResultIsRetriedOnce() throws Throwable {
        ProceedingJoinPoint pjp = joinPoint(new WebTool());
        when(pjp.proceed()).thenReturn(RETRYABLE_SAFE, OK);

        Object result = aspect(retryRegistry(2), bulkheadRegistry()).captureStep(pjp);

        assertThat(result).isSameAs(OK);
        verify(pjp, times(2)).proceed();
        assertThat(retries("WebTool")).isEqualTo(1.0);
        assertThat(calls("WebTool", ToolOutcomeStatus.SUCCESS)).isEqualTo(1.0);
        assertThat(logMessages()).anySatisfy(m -> assertThat(m)
                .startsWith("[ReAct] Retrying safe tool=WebTool after retryable failure, attempt=2"));
        assertThat(StepCollector.collect()).singleElement()
                .satisfies(step -> assertThat(step.status()).isEqualTo("success"));
    }

    @Test
    @DisplayName("重试用尽后返回最后一次结果（fail-after-max-attempts=false 语义），不抛异常")
    void exhaustedRetriesReturnLastResult() throws Throwable {
        ProceedingJoinPoint pjp = joinPoint(new WebTool());
        when(pjp.proceed()).thenReturn(RETRYABLE_SAFE);

        Object result = aspect(retryRegistry(2), bulkheadRegistry()).captureStep(pjp);

        assertThat(result).isSameAs(RETRYABLE_SAFE);
        verify(pjp, times(2)).proceed();
        assertThat(retries("WebTool")).isEqualTo(1.0);
        assertThat(calls("WebTool", ToolOutcomeStatus.RETRYABLE_FAILURE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("RETRYABLE_FAILURE 但 safeToRetry=false：绝不重试")
    void retryableUnsafeResultIsNotRetried() throws Throwable {
        ProceedingJoinPoint pjp = joinPoint(new BashTool());
        when(pjp.proceed()).thenReturn(RETRYABLE_UNSAFE, OK);

        Object result = aspect(retryRegistry(2), bulkheadRegistry()).captureStep(pjp);

        assertThat(result).isSameAs(RETRYABLE_UNSAFE);
        verify(pjp, times(1)).proceed();
        assertThat(retries("BashTool")).isZero();
    }

    @Test
    @DisplayName("PERMANENT_FAILURE 结果与普通 String 结果不触发重试")
    void nonRetryableResultsAreNotRetried() throws Throwable {
        ProceedingJoinPoint permanent = joinPoint(new WebTool());
        when(permanent.proceed()).thenReturn(PERMANENT, OK);
        ProceedingJoinPoint plain = joinPoint(new WebTool());
        when(plain.proceed()).thenReturn("hello");

        ToolExecutionAspect aspect = aspect(retryRegistry(2), bulkheadRegistry());
        assertThat(aspect.captureStep(permanent)).isSameAs(PERMANENT);
        assertThat(aspect.captureStep(plain)).isEqualTo("hello");

        verify(permanent, times(1)).proceed();
        verify(plain, times(1)).proceed();
        assertThat(retries("WebTool")).isZero();
    }

    @Test
    @DisplayName("configs.<ToolName> 优先于 configs.default：WebTool 允许 3 次尝试，BashTool 仍是默认 2 次")
    void namedConfigOverridesDefault() throws Throwable {
        RetryRegistry registry = RetryRegistry.of(Map.of(
                "default", retryConfig(2),
                "WebTool", retryConfig(3)));
        ToolExecutionAspect aspect = aspect(registry, bulkheadRegistry());

        ProceedingJoinPoint web = joinPoint(new WebTool());
        when(web.proceed()).thenReturn(RETRYABLE_SAFE);
        ProceedingJoinPoint bash = joinPoint(new BashTool());
        when(bash.proceed()).thenReturn(RETRYABLE_SAFE);

        aspect.captureStep(web);
        aspect.captureStep(bash);

        verify(web, times(3)).proceed();
        verify(bash, times(2)).proceed();
        assertThat(retries("WebTool")).isEqualTo(2.0);
        assertThat(retries("BashTool")).isEqualTo(1.0);
        // 实例仍注册在 registry 中（Micrometer 绑定依赖这一点），且谓词已换成我们的
        assertThat(registry.find("WebTool")).isPresent();
        assertThat(registry.find("WebTool").get().getRetryConfig().getMaxAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("starter 预建的 instances.<ToolName>（默认谓词=任何异常都重试）会被替换，异常仍不重试")
    void preRegisteredInstanceIsReplacedWithSafePredicates() throws Throwable {
        RetryRegistry registry = retryRegistry(2);
        Retry preBuilt = registry.retry("WebTool", retryConfig(3));   // 模拟 yml instances.WebTool
        assertThat(preBuilt.getRetryConfig().getExceptionPredicate().test(new IOException("x"))).isTrue();

        ProceedingJoinPoint pjp = joinPoint(new WebTool());
        when(pjp.proceed()).thenThrow(new IOException("boom"));

        assertThatThrownBy(() -> aspect(registry, bulkheadRegistry()).captureStep(pjp))
                .isInstanceOf(IOException.class);

        verify(pjp, times(1)).proceed();
        assertThat(retries("WebTool")).isZero();
        Retry replaced = registry.find("WebTool").orElseThrow();
        assertThat(replaced).isNotSameAs(preBuilt);
        assertThat(replaced.getRetryConfig().getMaxAttempts()).isEqualTo(3);   // 保留运维给的 maxAttempts
        assertThat(replaced.getRetryConfig().getExceptionPredicate().test(new IOException("x"))).isFalse();
    }

    @Test
    @DisplayName("同一工具多次调用复用同一个 Retry 实例，onRetry 监听器只注册一次（计数不翻倍）")
    void retryInstanceIsCachedPerTool() throws Throwable {
        RetryRegistry registry = retryRegistry(2);
        ToolExecutionAspect aspect = aspect(registry, bulkheadRegistry());

        for (int i = 0; i < 3; i++) {
            ProceedingJoinPoint pjp = joinPoint(new WebTool());
            when(pjp.proceed()).thenReturn(RETRYABLE_SAFE, OK);
            aspect.captureStep(pjp);
        }

        assertThat(retries("WebTool")).isEqualTo(3.0);
        assertThat(registry.getAllRetries()).hasSize(1);
        assertThat(logMessages().stream().filter(m -> m.startsWith("[ReAct] Retrying safe tool=WebTool")))
                .hasSize(3);
    }

    // -------------------------------------------------------------------------
    // Exceptions from the tool body
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("工具体抛出的异常（即便是可重试类型 IOException）绝不重试，原样抛出并归类为 retryable_failure")
    void exceptionsFromToolBodyAreNeverRetried() throws Throwable {
        ProceedingJoinPoint pjp = joinPoint(new BashTool());
        IOException boom = new IOException("boom");
        when(pjp.proceed()).thenThrow(boom);

        assertThatThrownBy(() -> aspect(retryRegistry(2), bulkheadRegistry()).captureStep(pjp))
                .isSameAs(boom);

        verify(pjp, times(1)).proceed();
        assertThat(retries("BashTool")).isZero();
        assertThat(calls("BashTool", ToolOutcomeStatus.RETRYABLE_FAILURE)).isEqualTo(1.0);
        assertThat(StepCollector.collect()).singleElement()
                .satisfies(step -> {
                    assertThat(step.status()).isEqualTo("retryable_failure");
                    assertThat(step.toolOutput()).isEqualTo("IOException: boom");
                });
    }

    @Test
    @DisplayName("CallNotPermittedException（LLM 熔断打开）：不重试、快速失败、归类 permanent_failure")
    void callNotPermittedFailsFastAsPermanentFailure() throws Throwable {
        CircuitBreaker breaker = CircuitBreaker.of("llmChat", CircuitBreakerConfig.ofDefaults());
        breaker.transitionToOpenState();
        CallNotPermittedException open = CallNotPermittedException.createCallNotPermittedException(breaker);

        ProceedingJoinPoint pjp = joinPoint(new WebTool());
        when(pjp.proceed()).thenThrow(new RuntimeException("wrapped", open));

        assertThatThrownBy(() -> aspect(retryRegistry(2), bulkheadRegistry()).captureStep(pjp))
                .isInstanceOf(RuntimeException.class)
                .hasCause(open);

        verify(pjp, times(1)).proceed();
        assertThat(retries("WebTool")).isZero();
        assertThat(calls("WebTool", ToolOutcomeStatus.PERMANENT_FAILURE)).isEqualTo(1.0);
        assertThat(StepCollector.collect()).singleElement()
                .satisfies(step -> assertThat(step.status()).isEqualTo("permanent_failure"));
    }

    // -------------------------------------------------------------------------
    // Bulkhead
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Bulkhead 满：BulkheadFullException 被重试一次（工具体没执行），许可释放后第二次成功")
    void bulkheadFullIsRetriedAndSucceedsOncePermitIsReleased() throws Throwable {
        BulkheadRegistry bulkheads = BulkheadRegistry.of(Map.of(
                "default", BulkheadConfig.custom().maxConcurrentCalls(32).maxWaitDuration(Duration.ZERO).build(),
                "WebTool", BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build()));
        // 模拟 starter 按 resilience4j.bulkhead.instances.WebTool 预建实例（configName = "WebTool"）
        Bulkhead webBulkhead = bulkheads.bulkhead("WebTool", "WebTool");
        assertThat(webBulkhead.getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(1);
        assertThat(webBulkhead.tryAcquirePermission()).isTrue();   // 占住唯一许可，模拟并发中的另一次调用

        // 切面在首次使用时才创建 Retry：借 registry 事件在它创建后挂一个 onRetry 监听器，
        // 在重试等待期间释放许可 —— 单线程、确定性地复现「第一次被拒、第二次成功」。
        AtomicInteger retryEvents = new AtomicInteger();
        RetryRegistry retries = RetryRegistry.of(retryConfig(2), new RegistryEventConsumer<Retry>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<Retry> event) {
                event.getAddedEntry().getEventPublisher().onRetry(e -> {
                    retryEvents.incrementAndGet();
                    webBulkhead.releasePermission();
                });
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<Retry> event) {
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<Retry> event) {
            }
        });

        ProceedingJoinPoint pjp = joinPoint(new WebTool());
        when(pjp.proceed()).thenReturn(OK);

        Object result = aspect(retries, bulkheads).captureStep(pjp);

        assertThat(result).isSameAs(OK);
        verify(pjp, times(1)).proceed();                     // 第一次尝试根本没进工具体
        assertThat(retryEvents.get()).isEqualTo(1);
        assertThat(retries("WebTool")).isEqualTo(1.0);
        assertThat(calls("WebTool", ToolOutcomeStatus.SUCCESS)).isEqualTo(1.0);
        assertThat(webBulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);   // 调用结束许可已归还
    }

    @Test
    @DisplayName("Bulkhead 持续满：重试用尽后 BulkheadFullException 原样抛出，归类 retryable_failure；configs.<ToolName> 生效")
    void bulkheadStillFullAfterRetriesSurfacesAsRetryableFailure() throws Throwable {
        BulkheadRegistry bulkheads = BulkheadRegistry.of(Map.of(
                "default", BulkheadConfig.custom().maxConcurrentCalls(32).maxWaitDuration(Duration.ZERO).build(),
                "BashTool", BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build()));
        ToolExecutionAspect aspect = aspect(retryRegistry(2), bulkheads);

        // 没有预建实例：切面应按 configs.BashTool（而不是 default）创建
        ProceedingJoinPoint warmup = joinPoint(new BashTool());
        when(warmup.proceed()).thenReturn(OK);
        aspect.captureStep(warmup);
        Bulkhead bashBulkhead = bulkheads.find("BashTool").orElseThrow();
        assertThat(bashBulkhead.getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(1);
        assertThat(bashBulkhead.tryAcquirePermission()).isTrue();

        ProceedingJoinPoint pjp = joinPoint(new BashTool());
        when(pjp.proceed()).thenReturn(OK);

        assertThatThrownBy(() -> aspect.captureStep(pjp))
                .isInstanceOf(BulkheadFullException.class);

        verify(pjp, times(0)).proceed();                     // 非幂等工具体一次都没执行
        assertThat(retries("BashTool")).isEqualTo(1.0);
        assertThat(calls("BashTool", ToolOutcomeStatus.RETRYABLE_FAILURE)).isEqualTo(1.0);
        assertThat(StepCollector.collect()).hasSize(2)
                .last().satisfies(step -> assertThat(step.status()).isEqualTo("retryable_failure"));
        bashBulkhead.releasePermission();
    }

    @Test
    @DisplayName("Bulkhead 许可在工具抛异常后也会归还")
    void bulkheadPermitIsReleasedAfterException() throws Throwable {
        BulkheadRegistry bulkheads = bulkheadRegistry();
        ProceedingJoinPoint pjp = joinPoint(new WebTool());
        when(pjp.proceed()).thenThrow(new IllegalStateException("bad"));

        assertThatThrownBy(() -> aspect(retryRegistry(2), bulkheads).captureStep(pjp))
                .isInstanceOf(IllegalStateException.class);

        assertThat(bulkheads.bulkhead("WebTool").getMetrics().getAvailableConcurrentCalls()).isEqualTo(32);
        assertThat(calls("WebTool", ToolOutcomeStatus.PERMANENT_FAILURE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("工具体抛出普通 RuntimeException：不重试，原样抛出，归类 permanent_failure，calls{status=error}")
    void plainRuntimeExceptionIsNotRetriedAndIsPermanentFailure() throws Throwable {
        ProceedingJoinPoint pjp = joinPoint(new WebTool());
        IllegalStateException boom = new IllegalStateException("x");
        when(pjp.proceed()).thenThrow(boom);

        assertThatThrownBy(() -> aspect(retryRegistry(2), bulkheadRegistry()).captureStep(pjp))
                .isSameAs(boom);

        verify(pjp, times(1)).proceed();
        assertThat(retries("WebTool")).isZero();
        assertThat(calls("WebTool", ToolOutcomeStatus.PERMANENT_FAILURE)).isEqualTo(1.0);
        assertThat(meterRegistry.counter("ai.tool.calls.total",
                "tool", "WebTool", "status", "error", "outcome", "permanent_failure").count()).isEqualTo(1.0);
        assertThat(StepCollector.collect()).singleElement()
                .satisfies(step -> assertThat(step.status()).isEqualTo("permanent_failure"));
    }

    // -------------------------------------------------------------------------
    // Interrupted thread
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("线程已中断：Bulkhead 抛 AcquirePermissionCancelledException，工具体不执行、不重试、归类 permanent_failure 且输出可读")
    void alreadyInterruptedThreadNeverRunsToolAndIsPermanentFailure() throws Throwable {
        ProceedingJoinPoint pjp = joinPoint(new BashTool());
        when(pjp.proceed()).thenReturn(OK);
        try {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> aspect(retryRegistry(2), bulkheadRegistry()).captureStep(pjp))
                    .isInstanceOf(AcquirePermissionCancelledException.class);

            verify(pjp, never()).proceed();
            assertThat(retries("BashTool")).isZero();
            assertThat(calls("BashTool", ToolOutcomeStatus.PERMANENT_FAILURE)).isEqualTo(1.0);
            assertThat(StepCollector.collect()).singleElement().satisfies(step -> {
                assertThat(step.status()).isEqualTo("permanent_failure");
                assertThat(step.toolOutput()).startsWith("ToolCallCancelled:");
            });
        } finally {
            Thread.interrupted();   // 清掉标志，避免污染后续用例
        }
    }

    @Test
    @DisplayName("结果型重试的 backoff 期间被中断：返回最后一次结果、恢复 interrupt 标志、不抛 NPE")
    void interruptDuringResultRetryBackoffReturnsLastResult() throws Throwable {
        // maxAttempts=2 + 长 waitDuration：首次返回 RETRYABLE_SAFE 触发 onRetry，随后 sleep 立刻因 interrupt 标志中断
        RetryRegistry registry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofSeconds(30))
                .build());
        ProceedingJoinPoint pjp = joinPoint(new WebTool());
        // 第一次 proceed 时线程尚未中断（Bulkhead 拿许可需要），返回结果后再置位，让退避 sleep 被中断
        when(pjp.proceed()).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            return RETRYABLE_SAFE;
        });
        try {
            long start = System.currentTimeMillis();
            Object result = aspect(registry, bulkheadRegistry()).captureStep(pjp);

            assertThat(System.currentTimeMillis() - start).isLessThan(5_000);
            assertThat(result).isSameAs(RETRYABLE_SAFE);
            verify(pjp, times(1)).proceed();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();   // 标志已恢复
            assertThat(retries("WebTool")).isEqualTo(1.0);                  // onRetry 在 sleep 前已发布
            assertThat(logMessages()).anySatisfy(m -> assertThat(m)
                    .startsWith("[ReAct] Tool retry interrupted during backoff"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("工具体自己抛的 NPE 不会被误判为退避中断，原样抛出")
    void toolBodyNpeIsNotSwallowedAsBackoffInterrupt() throws Throwable {
        ProceedingJoinPoint pjp = joinPoint(new WebTool());
        NullPointerException npe = new NullPointerException("tool bug");
        when(pjp.proceed()).thenThrow(npe);

        assertThatThrownBy(() -> aspect(retryRegistry(2), bulkheadRegistry()).captureStep(pjp))
                .isSameAs(npe);

        verify(pjp, times(1)).proceed();
        assertThat(calls("WebTool", ToolOutcomeStatus.PERMANENT_FAILURE)).isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------
    // Max steps
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("步数上限：抛 MaxStepsExceededException，工具体与 Retry/Bulkhead 都不执行，calls{outcome=refused}")
    void maxStepsExceededIsRefusedBeforeAnyExecution() throws Throwable {
        StepCollector.clear();
        StepCollector.init(0);
        BulkheadRegistry bulkheads = bulkheadRegistry();
        RetryRegistry retryRegistry = retryRegistry(2);
        ProceedingJoinPoint pjp = joinPoint(new WebTool());

        assertThatThrownBy(() -> aspect(retryRegistry, bulkheads).captureStep(pjp))
                .isInstanceOf(MaxStepsExceededException.class);

        verify(pjp, never()).proceed();
        assertThat(calls("WebTool", ToolOutcomeStatus.REFUSED)).isEqualTo(1.0);
        assertThat(retries("WebTool")).isZero();
        // 连 Retry / Bulkhead 实例都不应被创建
        assertThat(retryRegistry.getAllRetries()).isEmpty();
        assertThat(bulkheads.getAllBulkheads()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Bulkhead — real concurrency
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("真并发：named bulkhead maxConcurrentCalls=1，第二个并发调用快速失败（BulkheadFullException），第一个不受影响")
    void concurrentSecondCallFailsFastWhenNamedBulkheadIsFull() throws Throwable {
        BulkheadRegistry bulkheads = BulkheadRegistry.of(Map.of(
                "default", BulkheadConfig.custom().maxConcurrentCalls(32).maxWaitDuration(Duration.ZERO).build(),
                "WebTool", BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build()));
        ToolExecutionAspect aspect = aspect(retryRegistry(2), bulkheads);

        CountDownLatch entered = new CountDownLatch(1);   // 第一个调用已进入工具体
        CountDownLatch release = new CountDownLatch(1);   // 放第一个调用离开工具体

        ProceedingJoinPoint slow = joinPoint(new WebTool());
        when(slow.proceed()).thenAnswer(inv -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return OK;
        });
        ProceedingJoinPoint second = joinPoint(new WebTool());
        when(second.proceed()).thenReturn(OK);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // StepCollector 是 ThreadLocal：工作线程需要自己 init
            Future<Object> first = executor.submit(() -> {
                StepCollector.init(50);
                try {
                    return aspect.captureStep(slow);
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                } finally {
                    StepCollector.clear();
                }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            // 唯一许可被第一个调用占着：第二个调用（重试一次仍满）应快速失败
            assertThatThrownBy(() -> aspect.captureStep(second))
                    .isInstanceOf(BulkheadFullException.class);
            verify(second, never()).proceed();
            assertThat(retries("WebTool")).isEqualTo(1.0);
            assertThat(calls("WebTool", ToolOutcomeStatus.RETRYABLE_FAILURE)).isEqualTo(1.0);
            assertThat(StepCollector.collect()).singleElement()
                    .satisfies(step -> assertThat(step.status()).isEqualTo("retryable_failure"));

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(OK);
            verify(slow, times(1)).proceed();
            assertThat(calls("WebTool", ToolOutcomeStatus.SUCCESS)).isEqualTo(1.0);
            assertThat(bulkheads.bulkhead("WebTool").getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
