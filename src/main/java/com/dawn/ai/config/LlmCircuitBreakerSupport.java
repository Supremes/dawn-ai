package com.dawn.ai.config;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LLM 上游熔断支持：把 resilience4j {@link CircuitBreaker} 挂到 Spring AI 的两个 HTTP 出口上
 * （{@code openAiRestClientBuilder} 同步调用 / {@code openAiWebClientBuilder} 流式调用）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>按请求路径选择熔断器（D4）：{@code /embeddings} → {@value #EMBEDDING_BREAKER}；
 *       其余一律 → {@value #CHAT_BREAKER}（自定义网关 / 非标准 chat 路径也自动受保护）。</li>
 *   <li><b>deny-list 例外（返回 null 直接放行）</b>：仅 {@code /rerank} 等显式列出的非 LLM 路径。
 *       {@code CrossEncoderRetrievalReranker} 克隆了 @Primary 的 {@code openAiRestClientBuilder}
 *       去调 rerank 服务，rerank 挂掉不能把 chat 熔断器打开。</li>
 *   <li>失败判定：传输层异常（IOException / ResourceAccessException / 超时）、HTTP 429 和 5xx。
 *       其它 4xx（鉴权、参数错误）记为成功，避免配置错误把熔断器打开。</li>
 *   <li>状态在这里手动记录，不依赖 RestClient 的 error handler：Spring AI 的 ResponseErrorHandler
 *       在拦截器之外才抛 TransientAiException，拦截器里必须自己看 status。</li>
 *   <li>流式请求只在响应头到达时记一次结果；SSE body 中途断掉不会被记为失败，也绝不会被重试。</li>
 *   <li>熔断器 OPEN 时在发起任何网络请求前抛 {@link CallNotPermittedException}。</li>
 * </ul>
 */
public final class LlmCircuitBreakerSupport {

    private static final Logger log = LoggerFactory.getLogger(LlmCircuitBreakerSupport.class);

    public static final String CHAT_BREAKER = "llmChat";
    public static final String EMBEDDING_BREAKER = "llmEmbedding";

    private LlmCircuitBreakerSupport() {
    }

    /** 显式放行（不受熔断保护）的非 LLM 路径后缀。 */
    private static final String[] UNGUARDED_PATH_SUFFIXES = {"/rerank"};

    /**
     * 根据请求 URI 选择熔断器名称：{@code /embeddings} → llmEmbedding；deny-list 中的非 LLM 路径 → null（放行）；
     * 其余一律 llmChat。
     */
    static String breakerNameFor(URI uri) {
        String path = uri == null || uri.getPath() == null ? "" : uri.getPath();
        if (path.endsWith("/embeddings")) {
            return EMBEDDING_BREAKER;
        }
        for (String suffix : UNGUARDED_PATH_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return null;
            }
        }
        return CHAT_BREAKER;
    }

    /**
     * 429 与 5xx 视为上游失败；其它 4xx（鉴权 / 参数）不能打开熔断。
     */
    static boolean isFailureStatus(int status) {
        return status == 429 || status >= 500;
    }

    /**
     * 同步 RestClient 拦截器。必须注册在最外层（第一个），才能看到 logging 拦截器 / 传输层抛出的异常。
     */
    public static ClientHttpRequestInterceptor restClientInterceptor(CircuitBreakerRegistry registry) {
        return (request, body, execution) -> {
            String name = breakerNameFor(request.getURI());
            if (name == null) {
                return execution.execute(request, body);
            }
            CircuitBreaker cb = registry.circuitBreaker(name);
            if (!cb.tryAcquirePermission()) {
                log.warn("[AI HTTP] circuit {} is {} — rejecting {} {} before network call",
                        name, cb.getState(), request.getMethod(), request.getURI());
                throw CallNotPermittedException.createCallNotPermittedException(cb);
            }
            long start = cb.getCurrentTimestamp();
            TimeUnit unit = cb.getTimestampUnit();
            ClientHttpResponse response;
            try {
                response = execution.execute(request, body);
            } catch (Throwable t) {
                // IOException / ResourceAccessException / 超时 —— 传输层抛出的一切都算失败
                cb.onError(cb.getCurrentTimestamp() - start, unit, t);
                throw t;
            }
            int status = response.getStatusCode().value();
            if (isFailureStatus(status)) {
                cb.onError(cb.getCurrentTimestamp() - start, unit, new LlmUpstreamStatusException(name, status));
            } else {
                cb.onSuccess(cb.getCurrentTimestamp() - start, unit);
            }
            return response;
        };
    }

    /**
     * 流式 WebClient filter。结果在响应头到达（next.exchange 完成）时记录一次；
     * 每个获取到的 permission 恰好对应一次 onSuccess / onError / releasePermission（settled 守卫）。
     */
    public static ExchangeFilterFunction webClientFilter(CircuitBreakerRegistry registry) {
        return (request, next) -> {
            String name = breakerNameFor(request.url());
            if (name == null) {
                return next.exchange(request);
            }
            CircuitBreaker cb = registry.circuitBreaker(name);
            if (!cb.tryAcquirePermission()) {
                log.warn("[AI STREAM HTTP] circuit {} is {} — rejecting {} {} before network call",
                        name, cb.getState(), request.method(), request.url());
                return Mono.error(CallNotPermittedException.createCallNotPermittedException(cb));
            }
            long start = cb.getCurrentTimestamp();
            TimeUnit unit = cb.getTimestampUnit();
            AtomicBoolean settled = new AtomicBoolean(false);
            return next.exchange(request)
                    .doOnNext(resp -> {
                        if (settled.compareAndSet(false, true)) {
                            int status = resp.statusCode().value();
                            long elapsed = cb.getCurrentTimestamp() - start;
                            if (isFailureStatus(status)) {
                                cb.onError(elapsed, unit, new LlmUpstreamStatusException(name, status));
                            } else {
                                cb.onSuccess(elapsed, unit);
                            }
                        }
                    })
                    .doOnError(err -> {
                        if (settled.compareAndSet(false, true)) {
                            cb.onError(cb.getCurrentTimestamp() - start, unit, err);
                        }
                    })
                    .doOnCancel(() -> {
                        if (settled.compareAndSet(false, true)) {
                            cb.releasePermission();
                        }
                    });
        };
    }

    /**
     * 仅用于向熔断器记录 "上游返回了失败状态码"，不会向外抛出。
     */
    private static final class LlmUpstreamStatusException extends RuntimeException {
        LlmUpstreamStatusException(String breakerName, int status) {
            super("upstream " + breakerName + " returned HTTP " + status, null, false, false);
        }
    }
}
