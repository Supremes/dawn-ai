package com.dawn.ai.config;

import com.dawn.ai.agent.trace.StepCollectorContextAccessor;
import com.dawn.ai.exception.MaxStepsExceededException;
import io.micrometer.context.ContextRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

import java.util.ArrayList;
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
     *
     * <p>Uses {@link ThreadPoolExecutor.AbortPolicy} so that overload is surfaced as a
     * {@link java.util.concurrent.RejectedExecutionException} that {@code ChatService}
     * converts to a {@code CAPACITY_EXCEEDED} error event and an HTTP-level 503, rather
     * than silently falling back to the Tomcat servlet thread (which would block it for
     * the full 120 s SSE timeout).
     */
    @Bean(name = "chatStreamExecutor", destroyMethod = "shutdown")
    public ExecutorService chatStreamExecutor() {
        return new ThreadPoolExecutor(
                8, 32,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "chat-stream-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Bean(name = "ragRetrievalExecutor", destroyMethod = "shutdown")
    public ExecutorService ragRetrievalExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                4, 16,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(128),
                r -> {
                    Thread t = new Thread(r, "rag-retrieval-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return new SessionAwareExecutor(pool);
    }

    /**
     * 为响应式（Reactor）管道启用 Micrometer 上下文传播。
     *
     * <p>若不启用，{@link com.dawn.ai.agent.trace.StepCollector} 的 ThreadLocal 状态
     * 对 Reactor Netty 工作线程不可见——而 Spring AI 的 tool callback 在流式响应期间
     * 正是运行在这些线程上，从而导致 NPE 和 tool 调用静默失败。
     *
     * <p>工作原理：
     * <ol>
     *   <li>将 {@link StepCollectorContextAccessor} 注册到 Micrometer 的
     *       {@link ContextRegistry}，告知 Micrometer 哪些 ThreadLocal 需要被捕获。</li>
     *   <li>{@link Hooks#enableAutomaticContextPropagation()} 指示 Reactor 在订阅时
     *       将所有已注册的 ThreadLocal 捕获到响应式管道上下文中，并在每个操作符执行前
     *       自动恢复——即使发生了线程切换。</li>
     *   <li>由于 {@link com.dawn.ai.agent.trace.StepCollectorContext} 是按
     *       <em>引用</em> 传播的，所有线程共享同一个可变状态对象，从而保证跨线程的
     *       步骤计数与收集结果正确。</li>
     * </ol>
     */
    @Bean
    public ApplicationRunner enableReactorContextPropagation() {
        return args -> {
            // 告诉 Micrometer：存在一些 ThreadLocal 需要传播
            ContextRegistry.getInstance()
                    .registerThreadLocalAccessor(new StepCollectorContextAccessor())
                    .registerThreadLocalAccessor(new AiInteractionContextAccessor());
            // 告诉 Reactor：每次切换线程前自动调用所有 Accessor 的 set/restore
            Hooks.enableAutomaticContextPropagation();
            log.info("[AgentConfig] Reactor automatic context propagation enabled (StepCollector, AiInteractionContext)");
        };
    }


    /**
     * Embedding 批处理策略。覆盖 Spring AI pgvector 自动配置提供的默认
     * {@link TokenCountBatchingStrategy}（默认仅按 token 总量分批，不限制条数）。
     *
     * <p>DashScope 的 {@code text-embedding-v4} 接口对单次 embeddings 请求有
     * "input.contents 最多 10 条" 的硬限制，超过会直接返回 HTTP 400。短文本场景下
     * 多个 chunk 的 token 总量很容易低于 token 上限却超过 10 条，因此这里先用 token
     * 策略保证不超 token 上限，再把每个子批进一步按条数切到 {@code maxBatchSize}。
     */
    @Bean
    public BatchingStrategy embeddingBatchingStrategy(
            @Value("${app.ai.embedding.max-batch-size:10}") int maxBatchSize) {
        TokenCountBatchingStrategy tokenStrategy = new TokenCountBatchingStrategy();
        return documents -> {
            List<List<Document>> result = new ArrayList<>();
            for (List<Document> tokenBatch : tokenStrategy.batch(documents)) {
                for (int i = 0; i < tokenBatch.size(); i += maxBatchSize) {
                    int end = Math.min(i + maxBatchSize, tokenBatch.size());
                    result.add(new ArrayList<>(tokenBatch.subList(i, end)));
                }
            }
            return result;
        };
    }
}
