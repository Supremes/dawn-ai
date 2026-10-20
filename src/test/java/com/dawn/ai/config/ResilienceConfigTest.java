package com.dawn.ai.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 {@link ResilienceConfig} 注册的 RegistryEventConsumer 会把关键事件打成 WARN 日志。
 * 全部使用内存 Registry，不依赖 Spring 容器 / 网络。
 */
class ResilienceConfigTest {

    private final ResilienceConfig config = new ResilienceConfig();
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(ResilienceConfig.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(null);
    }

    @Test
    @DisplayName("CircuitBreaker 状态迁移与失败率超阈值应打 WARN；被拒绝调用只打 DEBUG")
    void circuitBreakerTransitionsAreLoggedAtWarn() {
        RegistryEventConsumer<CircuitBreaker> consumer = config.circuitBreakerEventLogger();
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(cbConfig, List.of(consumer));

        CircuitBreaker cb = registry.circuitBreaker("llmChat");
        cb.onError(1, TimeUnit.MILLISECONDS, new IOException("boom"));
        cb.onError(1, TimeUnit.MILLISECONDS, new IOException("boom"));
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 熔断期间再申请许可 -> onCallNotPermitted
        assertThat(cb.tryAcquirePermission()).isFalse();

        List<ILoggingEvent> warns = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warns).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("[Resilience] CircuitBreaker llmChat state CLOSED -> OPEN"));
        assertThat(warns).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("[Resilience] CircuitBreaker llmChat failure rate")
                .contains("exceeded threshold"));

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(e.getFormattedMessage()).contains("[Resilience] CircuitBreaker llmChat rejected a call (OPEN)");
        });
    }

    @Test
    @DisplayName("Retry 用尽后仍失败应打 WARN，但单次重试本身不打日志（由 aspect 负责）")
    void retryExhaustionIsLoggedAtWarn() {
        RegistryEventConsumer<Retry> consumer = config.retryEventLogger();
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(1))
                .build();
        RetryRegistry registry = RetryRegistry.of(retryConfig, List.of(consumer));

        Retry retry = registry.retry("WebTool");
        assertThatThrownBy(() -> Retry.decorateCheckedSupplier(retry, () -> {
            throw new IOException("upstream down");
        }).get()).isInstanceOf(IOException.class);

        List<String> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0))
                .contains("[Resilience] Retry WebTool exhausted after 2 attempts")
                .contains("upstream down");
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("Retrying"));
    }

    @Test
    @DisplayName("Bulkhead 并发打满被拒绝时应打 WARN")
    void bulkheadRejectionIsLoggedAtWarn() {
        RegistryEventConsumer<Bulkhead> consumer = config.bulkheadEventLogger();
        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO)
                .build();
        BulkheadRegistry registry = BulkheadRegistry.of(bulkheadConfig, List.of(consumer));

        Bulkhead bulkhead = registry.bulkhead("BashTool");
        assertThat(bulkhead.tryAcquirePermission()).isTrue();
        try {
            assertThatThrownBy(() -> bulkhead.executeSupplier(() -> "never"))
                    .isInstanceOf(BulkheadFullException.class);
        } finally {
            bulkhead.releasePermission();
        }

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage())
                    .contains("[Resilience] Bulkhead BashTool rejected a call (concurrent limit reached)");
        });
    }

    @Test
    @DisplayName("Registry 中替换实例后，新实例同样挂上监听")
    void replacedEntryIsAlsoAttached() {
        RegistryEventConsumer<CircuitBreaker> consumer = config.circuitBreakerEventLogger();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults(), List.of(consumer));
        registry.circuitBreaker("llmEmbedding");

        CircuitBreaker replacement = CircuitBreaker.ofDefaults("llmEmbedding");
        registry.replace("llmEmbedding", replacement);
        replacement.transitionToOpenState();

        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("CircuitBreaker llmEmbedding state CLOSED -> OPEN");
        });
    }
}
