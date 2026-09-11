package com.dawn.ai.exception;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClientException;

import java.net.HttpRetryException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CallNotPermittedException (LLM circuit breaker OPEN) must surface as HTTP 503 with the
 * llm_circuit_open error type — whether thrown directly or wrapped by Spring / Reactor.
 */
class ApiExceptionHandlerCircuitBreakerTest {

    private SimpleMeterRegistry meterRegistry;
    private ApiExceptionHandler handler;
    private CallNotPermittedException circuitOpen;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        handler = new ApiExceptionHandler(meterRegistry);
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("llmChat");
        breaker.transitionToOpenState();
        circuitOpen = CallNotPermittedException.createCallNotPermittedException(breaker);
    }

    @Test
    void directCallNotPermitted_shouldReturn503AndRecordLlmCircuitOpen() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleCircuitOpen(circuitOpen, request("/api/chat"));

        assertServiceUnavailable(response, "/api/chat");
        assertThat(errorCount("llm_circuit_open")).isEqualTo(1.0);
    }

    @Test
    void wrappedInRestClientException_shouldReturn503_notBadGateway() {
        RestClientException wrapped = new RestClientException("I/O error on POST request", circuitOpen);

        ResponseEntity<Map<String, Object>> response =
                handler.handleRestClient(wrapped, request("/api/chat"));

        assertServiceUnavailable(response, "/api/chat");
        assertThat(errorCount("llm_circuit_open")).isEqualTo(1.0);
        assertThat(errorCount("llm_error")).isZero();
    }

    @Test
    void wrappedDeepInUnexpectedException_shouldReturn503_notInternalError() {
        Exception wrapped = new RuntimeException("outer",
                new IllegalStateException("middle", circuitOpen));

        ResponseEntity<Map<String, Object>> response =
                handler.handleUnexpected(wrapped, request("/api/rag/query"));

        assertServiceUnavailable(response, "/api/rag/query");
        assertThat(errorCount("llm_circuit_open")).isEqualTo(1.0);
        assertThat(errorCount("internal_error")).isZero();
    }

    @Test
    void unrelatedRestClientException_shouldKeepBadGatewayPath() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleRestClient(new RestClientException("boom"), request("/api/chat"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(errorCount("llm_error")).isEqualTo(1.0);
        assertThat(errorCount("llm_circuit_open")).isZero();
    }

    @Test
    void unrelatedUnexpectedException_shouldKeepInternalErrorPath() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleUnexpected(new RuntimeException("boom"), request("/api/chat"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(errorCount("internal_error")).isEqualTo(1.0);
    }

    @Test
    void httpRetryExceptionCause_shouldStillMapToBadGatewayAuthError_regressionGuard() {
        RestClientException wrapped = new RestClientException("auth", new HttpRetryException("401", 401));

        ResponseEntity<Map<String, Object>> response =
                handler.handleRestClient(wrapped, request("/api/chat"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(errorCount("llm_auth_error")).isEqualTo(1.0);
        assertThat(errorCount("llm_circuit_open")).isZero();
    }

    @Test
    void circuitOpenMessage_shouldTellClientToRetryLater() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleCircuitOpen(circuitOpen, request("/api/chat/stream"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(String.valueOf(response.getBody().get("message"))).contains("temporarily unavailable");
    }

    private static void assertServiceUnavailable(ResponseEntity<Map<String, Object>> response, String path) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo(503);
        assertThat(body.get("path")).isEqualTo(path);
        assertThat(String.valueOf(body.get("message"))).contains("circuit breaker");
    }

    private double errorCount(String type) {
        var counter = meterRegistry.find("ai.error.total").tag("type", type).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static MockHttpServletRequest request(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }
}
