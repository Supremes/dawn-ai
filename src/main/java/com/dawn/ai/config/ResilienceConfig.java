package com.dawn.ai.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.github.resilience4j.retry.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j 事件日志接线。
 *
 * <p>resilience4j-spring-boot3 会自动装配 {@code RetryRegistry} / {@code BulkheadRegistry} / {@code CircuitBreakerRegistry}，
 * 并通过 resilience4j-micrometer 自动导出 {@code resilience4j_*} Prometheus 指标（无需在这里注册 MeterBinder）。
 * 本类只补一件事：给每个进入 Registry 的实例挂上事件监听，把关键状态变化打到应用日志里（前缀 {@code [Resilience]}），
 * 方便排查时不用先去看 /actuator/circuitbreakerevents。
 *
 * <p>三类日志：
 * <ol>
 *   <li><b>CircuitBreaker</b>：状态迁移（CLOSED → OPEN → HALF_OPEN → ...）与失败率超阈值打 WARN；
 *       熔断期间被拒绝的调用只打 DEBUG（可能高频）。</li>
 *   <li><b>Retry</b>：重试用尽仍失败打 WARN。这里刻意<b>不</b>监听 onRetry，
 *       因为 {@code ToolExecutionAspect} 已经在每次重试时输出 {@code [ReAct] Retrying safe tool=...}，避免重复日志。</li>
 *   <li><b>Bulkhead</b>：并发数打满导致调用被拒绝时打 WARN。</li>
 * </ol>
 *
 * <p>resilience4j-spring6 的 RetryConfiguration / CircuitBreakerConfiguration / BulkheadConfiguration
 * 会收集所有 {@code RegistryEventConsumer<X>} 类型的 bean 并注入对应 Registry，所以只需声明为 bean 即可，
 * 不需要手动 {@code registry.getEventPublisher().onEntryAdded(...)}。
 */
@Slf4j
@Configuration
public class ResilienceConfig {

    @Bean
    public RegistryEventConsumer<CircuitBreaker> circuitBreakerEventLogger() {
        return new RegistryEventConsumer<>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> event) {
                attach(event.getAddedEntry());
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<CircuitBreaker> event) {
                // no-op
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<CircuitBreaker> event) {
                attach(event.getNewEntry());
            }
        };
    }

    @Bean
    public RegistryEventConsumer<Retry> retryEventLogger() {
        return new RegistryEventConsumer<>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<Retry> event) {
                attach(event.getAddedEntry());
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<Retry> event) {
                // no-op
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<Retry> event) {
                attach(event.getNewEntry());
            }
        };
    }

    @Bean
    public RegistryEventConsumer<Bulkhead> bulkheadEventLogger() {
        return new RegistryEventConsumer<>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<Bulkhead> event) {
                attach(event.getAddedEntry());
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<Bulkhead> event) {
                // no-op
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<Bulkhead> event) {
                attach(event.getNewEntry());
            }
        };
    }

    private static void attach(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher()
                .onStateTransition(ev -> log.warn("[Resilience] CircuitBreaker {} state {} -> {}",
                        ev.getCircuitBreakerName(),
                        ev.getStateTransition().getFromState(),
                        ev.getStateTransition().getToState()))
                .onFailureRateExceeded(ev -> log.warn("[Resilience] CircuitBreaker {} failure rate {}% exceeded threshold",
                        ev.getCircuitBreakerName(), ev.getFailureRate()))
                .onCallNotPermitted(ev -> log.debug("[Resilience] CircuitBreaker {} rejected a call (OPEN)",
                        ev.getCircuitBreakerName()));
    }

    private static void attach(Retry retry) {
        retry.getEventPublisher()
                .onError(ev -> log.warn("[Resilience] Retry {} exhausted after {} attempts: {}",
                        ev.getName(),
                        ev.getNumberOfRetryAttempts(),
                        ev.getLastThrowable() == null ? "result still retryable" : ev.getLastThrowable().toString()));
    }

    private static void attach(Bulkhead bulkhead) {
        bulkhead.getEventPublisher()
                .onCallRejected(ev -> log.warn("[Resilience] Bulkhead {} rejected a call (concurrent limit reached)",
                        ev.getBulkheadName()));
    }
}
