package com.dawn.ai.config;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LlmCircuitBreakerSupport}: breaker naming, status classification,
 * RestClient interceptor and WebClient filter bookkeeping. No Spring context, no network.
 */
class LlmCircuitBreakerSupportTest {

    private static final byte[] EMPTY_BODY = new byte[0];

    private CircuitBreakerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .permittedNumberOfCallsInHalfOpenState(1)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .build());
    }

    // ---------- naming / status helpers ----------

    @Test
    void breakerNameFor_shouldRouteByPath() {
        assertThat(LlmCircuitBreakerSupport.breakerNameFor(URI.create("https://api.x.com/v1/chat/completions")))
                .isEqualTo("llmChat");
        assertThat(LlmCircuitBreakerSupport.breakerNameFor(URI.create("https://api.x.com/v1/completions")))
                .isEqualTo("llmChat");
        assertThat(LlmCircuitBreakerSupport.breakerNameFor(URI.create("https://api.x.com/v1/responses")))
                .isEqualTo("llmChat");
        assertThat(LlmCircuitBreakerSupport.breakerNameFor(URI.create("https://api.x.com/v1/embeddings")))
                .isEqualTo("llmEmbedding");
        // rerank (CrossEncoderRetrievalReranker clones the @Primary builder) is the only explicit deny-list entry
        assertThat(LlmCircuitBreakerSupport.breakerNameFor(URI.create("http://localhost:9000/rerank"))).isNull();
        // D4: anything else (custom gateway path, empty path) defaults to llmChat
        assertThat(LlmCircuitBreakerSupport.breakerNameFor(URI.create("https://gw.internal/llm/v2/generate")))
                .isEqualTo("llmChat");
        assertThat(LlmCircuitBreakerSupport.breakerNameFor(URI.create("http://localhost:9000"))).isEqualTo("llmChat");
    }

    @Test
    void isFailureStatus_shouldOnlyFlag429And5xx() {
        assertThat(LlmCircuitBreakerSupport.isFailureStatus(200)).isFalse();
        assertThat(LlmCircuitBreakerSupport.isFailureStatus(400)).isFalse();
        assertThat(LlmCircuitBreakerSupport.isFailureStatus(401)).isFalse();
        assertThat(LlmCircuitBreakerSupport.isFailureStatus(404)).isFalse();
        assertThat(LlmCircuitBreakerSupport.isFailureStatus(429)).isTrue();
        assertThat(LlmCircuitBreakerSupport.isFailureStatus(500)).isTrue();
        assertThat(LlmCircuitBreakerSupport.isFailureStatus(502)).isTrue();
        assertThat(LlmCircuitBreakerSupport.isFailureStatus(503)).isTrue();
    }

    // ---------- RestClient interceptor ----------

    @Test
    void restInterceptor_shouldRecordSuccessFor2xxAndOther4xx() throws IOException {
        ClientHttpRequestInterceptor interceptor = LlmCircuitBreakerSupport.restClientInterceptor(registry);

        interceptor.intercept(request("/v1/chat/completions"), EMPTY_BODY, execution(200));
        interceptor.intercept(request("/v1/chat/completions"), EMPTY_BODY, execution(401));

        CircuitBreaker.Metrics m = registry.circuitBreaker("llmChat").getMetrics();
        assertThat(m.getNumberOfSuccessfulCalls()).isEqualTo(2);
        assertThat(m.getNumberOfFailedCalls()).isZero();
    }

    @Test
    void restInterceptor_shouldRecordFailureFor429And5xxAndReturnResponse() throws IOException {
        ClientHttpRequestInterceptor interceptor = LlmCircuitBreakerSupport.restClientInterceptor(registry);

        ClientHttpResponse r1 = interceptor.intercept(request("/v1/chat/completions"), EMPTY_BODY, execution(429));
        ClientHttpResponse r2 = interceptor.intercept(request("/v1/chat/completions"), EMPTY_BODY, execution(503));

        // response is returned untouched so downstream (logging interceptor / error handler) still sees it
        assertThat(r1.getStatusCode().value()).isEqualTo(429);
        assertThat(r2.getStatusCode().value()).isEqualTo(503);
        CircuitBreaker.Metrics m = registry.circuitBreaker("llmChat").getMetrics();
        assertThat(m.getNumberOfFailedCalls()).isEqualTo(2);
        assertThat(m.getNumberOfSuccessfulCalls()).isZero();
    }

    @Test
    void restInterceptor_shouldRecordFailureOnTransportExceptionAndRethrow() throws IOException {
        ClientHttpRequestInterceptor interceptor = LlmCircuitBreakerSupport.restClientInterceptor(registry);
        ClientHttpRequestExecution failing = mock(ClientHttpRequestExecution.class);
        when(failing.execute(any(), any())).thenThrow(new IOException("connection reset"));

        assertThatThrownBy(() -> interceptor.intercept(request("/v1/embeddings"), EMPTY_BODY, failing))
                .isInstanceOf(IOException.class)
                .hasMessage("connection reset");

        CircuitBreaker.Metrics m = registry.circuitBreaker("llmEmbedding").getMetrics();
        assertThat(m.getNumberOfFailedCalls()).isEqualTo(1);
        // chat breaker untouched
        assertThat(registry.find("llmChat")).isEmpty();
    }

    @Test
    void restInterceptor_shouldFailFastWhenBreakerOpen_withoutCallingExecution() throws IOException {
        ClientHttpRequestInterceptor interceptor = LlmCircuitBreakerSupport.restClientInterceptor(registry);
        registry.circuitBreaker("llmChat").transitionToOpenState();
        ClientHttpRequestExecution execution = execution(200);

        assertThatThrownBy(() -> interceptor.intercept(request("/v1/chat/completions"), EMPTY_BODY, execution))
                .isInstanceOf(CallNotPermittedException.class);

        verify(execution, never()).execute(any(), any());
        assertThat(registry.circuitBreaker("llmChat").getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1);
    }

    @Test
    void restInterceptor_shouldOpenBreakerAfterRepeated5xx() throws IOException {
        ClientHttpRequestInterceptor interceptor = LlmCircuitBreakerSupport.restClientInterceptor(registry);
        for (int i = 0; i < 5; i++) {
            interceptor.intercept(request("/v1/chat/completions"), EMPTY_BODY, execution(502));
        }
        assertThat(registry.circuitBreaker("llmChat").getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void restInterceptor_shouldPassThroughUnknownPathsUnguarded() throws IOException {
        ClientHttpRequestInterceptor interceptor = LlmCircuitBreakerSupport.restClientInterceptor(registry);
        registry.circuitBreaker("llmChat").transitionToOpenState();

        ClientHttpResponse response = interceptor.intercept(request("/rerank"), EMPTY_BODY, execution(500));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        // no breaker created for the unknown path, and llmChat metrics untouched
        assertThat(registry.getAllCircuitBreakers()).hasSize(1);
        assertThat(registry.circuitBreaker("llmChat").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    // ---------- WebClient filter ----------

    @Test
    void webFilter_shouldRecordSuccessWhenResponseHeadArrives() {
        ExchangeFilterFunction filter = LlmCircuitBreakerSupport.webClientFilter(registry);
        ExchangeFunction next = req -> Mono.just(ClientResponse.create(HttpStatus.OK).build());

        ClientResponse response = filter.filter(clientRequest("/v1/chat/completions"), next).block();

        assertThat(response).isNotNull();
        CircuitBreaker.Metrics m = registry.circuitBreaker("llmChat").getMetrics();
        assertThat(m.getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(m.getNumberOfFailedCalls()).isZero();
    }

    @Test
    void webFilter_shouldRecordFailureFor5xxAnd429ButNotOther4xx() {
        ExchangeFilterFunction filter = LlmCircuitBreakerSupport.webClientFilter(registry);

        filter.filter(clientRequest("/v1/chat/completions"),
                req -> Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build())).block();
        filter.filter(clientRequest("/v1/chat/completions"),
                req -> Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).build())).block();
        filter.filter(clientRequest("/v1/chat/completions"),
                req -> Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).build())).block();

        CircuitBreaker.Metrics m = registry.circuitBreaker("llmChat").getMetrics();
        assertThat(m.getNumberOfFailedCalls()).isEqualTo(2);
        assertThat(m.getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    void webFilter_shouldRecordFailureOnErrorSignal() {
        ExchangeFilterFunction filter = LlmCircuitBreakerSupport.webClientFilter(registry);
        ExchangeFunction next = req -> Mono.error(new ResourceAccessException("timeout"));

        Mono<ClientResponse> result = filter.filter(clientRequest("/v1/embeddings"), next);
        assertThatThrownBy(result::block).isInstanceOf(ResourceAccessException.class);

        assertThat(registry.circuitBreaker("llmEmbedding").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void webFilter_shouldFailFastWhenBreakerOpen_withoutCallingNext() {
        ExchangeFilterFunction filter = LlmCircuitBreakerSupport.webClientFilter(registry);
        registry.circuitBreaker("llmChat").transitionToOpenState();
        ExchangeFunction next = mock(ExchangeFunction.class);

        Mono<ClientResponse> result = filter.filter(clientRequest("/v1/chat/completions"), next);
        assertThatThrownBy(result::block).isInstanceOf(CallNotPermittedException.class);

        verify(next, never()).exchange(any());
    }

    @Test
    void webFilter_shouldReleasePermissionOnCancelBeforeResponse() {
        ExchangeFilterFunction filter = LlmCircuitBreakerSupport.webClientFilter(registry);
        CircuitBreaker cb = registry.circuitBreaker("llmChat");
        // HALF_OPEN with 1 permitted call makes permission accounting observable
        cb.transitionToOpenState();
        cb.transitionToHalfOpenState();

        Disposable inFlight = filter.filter(clientRequest("/v1/chat/completions"), req -> Mono.never()).subscribe();
        // the single half-open permit is taken
        assertThat(cb.tryAcquirePermission()).isFalse();

        inFlight.dispose();

        // permit returned, no call recorded
        assertThat(cb.tryAcquirePermission()).isTrue();
        cb.releasePermission();
        assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isZero();
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void webFilter_shouldPassThroughUnknownPathsUnguarded() {
        ExchangeFilterFunction filter = LlmCircuitBreakerSupport.webClientFilter(registry);
        registry.circuitBreaker("llmChat").transitionToOpenState();

        ClientResponse response = filter.filter(clientRequest("/rerank"),
                req -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build())).block();

        assertThat(response).isNotNull();
        assertThat(response.statusCode().value()).isEqualTo(500);
        assertThat(registry.getAllCircuitBreakers()).hasSize(1);
    }

    @Test
    void restInterceptor_shouldRecordFailureOnResourceAccessException() throws IOException {
        ClientHttpRequestInterceptor interceptor = LlmCircuitBreakerSupport.restClientInterceptor(registry);
        ClientHttpRequestExecution failing = mock(ClientHttpRequestExecution.class);
        ResourceAccessException timeout = new ResourceAccessException("t");
        when(failing.execute(any(), any())).thenThrow(timeout);

        assertThatThrownBy(() -> interceptor.intercept(request("/v1/chat/completions"), EMPTY_BODY, failing))
                .isSameAs(timeout);

        assertThat(registry.circuitBreaker("llmChat").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void restInterceptor_shouldOpenAfterTwo5xxWithSmallWindow_andRejectThirdCall() throws IOException {
        CircuitBreakerRegistry small = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .build());
        ClientHttpRequestInterceptor interceptor = LlmCircuitBreakerSupport.restClientInterceptor(small);

        interceptor.intercept(request("/v1/chat/completions"), EMPTY_BODY, execution(500));
        interceptor.intercept(request("/v1/chat/completions"), EMPTY_BODY, execution(500));
        assertThat(small.circuitBreaker("llmChat").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        ClientHttpRequestExecution third = execution(200);
        assertThatThrownBy(() -> interceptor.intercept(request("/v1/chat/completions"), EMPTY_BODY, third))
                .isInstanceOf(CallNotPermittedException.class);
        verify(third, never()).execute(any(), any());
    }

    @Test
    void webFilter_shouldFailFastWhenBreakerOpen_nextNeverInvoked_flagStaysFalse() {
        ExchangeFilterFunction filter = LlmCircuitBreakerSupport.webClientFilter(registry);
        registry.circuitBreaker("llmChat").transitionToOpenState();
        AtomicBoolean invoked = new AtomicBoolean(false);
        ExchangeFunction next = req -> {
            invoked.set(true);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };

        assertThatThrownBy(() -> filter.filter(clientRequest("/v1/chat/completions"), next).block())
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(invoked).isFalse();
    }

    @Test
    void webFilter_shouldRecordFailureOnConnectException() {
        ExchangeFilterFunction filter = LlmCircuitBreakerSupport.webClientFilter(registry);
        ExchangeFunction next = req -> Mono.error(new ConnectException("refused"));

        assertThatThrownBy(() -> filter.filter(clientRequest("/v1/chat/completions"), next).block())
                .hasRootCauseInstanceOf(ConnectException.class);

        assertThat(registry.circuitBreaker("llmChat").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    // ---------- slow-call config regression ----------

    @Test
    void ymlMirroredConfig_shouldNotOpenOnSlowSuccessfulCalls() {
        // 与 application.yml resilience4j.circuitbreaker.configs.llm 一致：
        // 慢调用熔断靠 slow-call-duration-threshold=20m 关闭，而不是 rate=100（那是 >= 阈值，100% 慢照样 OPEN）
        CircuitBreakerConfig ymlLike = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .slowCallDurationThreshold(Duration.ofMinutes(20))
                .slowCallRateThreshold(100)
                .build();
        CircuitBreaker cb = CircuitBreakerRegistry.of(ymlLike).circuitBreaker("llmChat");

        // 同步 chat（planner / reflection / summarization .call()）跑 2~10 分钟属于正常
        for (int i = 0; i < 10; i++) {
            cb.onSuccess(Duration.ofMinutes(10).toNanos(), TimeUnit.NANOSECONDS);
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.getMetrics().getNumberOfSlowCalls()).isZero();
    }

    @Test
    void slowCallRateThreshold100_aloneDoesNotDisableSlowCallBreaking() {
        // 锁定 finding 里的事实：只把 rate 设成 100、duration 仍是 60s，>60s 的成功调用会把熔断器打开
        CircuitBreakerConfig oldConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofSeconds(60))
                .slowCallRateThreshold(100)
                .build();
        CircuitBreaker cb = CircuitBreakerRegistry.of(oldConfig).circuitBreaker("llmChat");

        for (int i = 0; i < 5; i++) {
            cb.onSuccess(Duration.ofSeconds(90).toNanos(), TimeUnit.NANOSECONDS);
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ---------- helpers ----------

    private static HttpRequest request(String path) {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("https://api.example.com" + path));
        when(request.getMethod()).thenReturn(HttpMethod.POST);
        return request;
    }

    private static ClientHttpRequestExecution execution(int status) throws IOException {
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getStatusCode()).thenReturn(HttpStatusCode.valueOf(status));
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(response);
        return execution;
    }

    private static ClientRequest clientRequest(String path) {
        return ClientRequest.create(HttpMethod.POST, URI.create("https://api.example.com" + path)).build();
    }
}
