package com.dawn.ai.config;

import com.dawn.ai.agent.trace.StepCollectorContextAccessor;
import com.dawn.ai.exception.MaxStepsExceededException;
import io.micrometer.context.ContextRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class AgentConfig {

    @Bean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return DefaultToolExecutionExceptionProcessor.builder()
                .rethrowExceptions(List.of(MaxStepsExceededException.class))
                .build();
    }

    /**
     * Dedicated thread pool for SSE streaming requests.
     * Each active SSE stream occupies one thread for the duration of the request.
     */
    @Bean(name = "chatStreamExecutor", destroyMethod = "shutdown")
    public ExecutorService chatStreamExecutor() {
        return new ThreadPoolExecutor(
                8, 32,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "chat-stream-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * Enables Micrometer context propagation for the reactive (Reactor) pipeline.
     *
     * <p>Without this, {@link com.dawn.ai.agent.trace.StepCollector}'s ThreadLocal state
     * is invisible to Reactor Netty worker threads that execute Spring AI tool callbacks
     * during streaming, causing NPEs and silent tool failures.
     *
     * <p>How it works:
     * <ol>
     *   <li>We register {@link StepCollectorContextAccessor} with Micrometer's
     *       {@link ContextRegistry} so Micrometer knows which ThreadLocal to capture.</li>
     *   <li>{@link Hooks#enableAutomaticContextPropagation()} tells Reactor to capture all
     *       registered ThreadLocals into the reactive pipeline context at subscribe time,
     *       and restore them before every operator — even across thread hops.</li>
     *   <li>Since {@link com.dawn.ai.agent.trace.StepCollectorContext} is propagated by
     *       <em>reference</em>, all threads share the same mutable state object, making
     *       step counting and collection correct across threads.</li>
     * </ol>
     */
    @Bean
    public ApplicationRunner enableReactorContextPropagation() {
        return args -> {
            ContextRegistry.getInstance()
                    .registerThreadLocalAccessor(new StepCollectorContextAccessor());
            Hooks.enableAutomaticContextPropagation();
            log.info("[AgentConfig] Reactor automatic context propagation enabled (StepCollector)");
        };
    }
}
