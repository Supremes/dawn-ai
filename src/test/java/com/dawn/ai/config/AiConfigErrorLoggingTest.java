package com.dawn.ai.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 上游 4xx 可见性修复的回归测试。
 *
 * 之前的 bug：openAiRestClientBuilder 用的是 SimpleClientHttpRequestFactory（JDK
 * HttpURLConnection）。上游返回 4xx 时，getInputStream() 抛 IOException，
 * Spring 把它包成 ResourceAccessException —— provider 返回的真实错误 body
 * 从来没被读出来，也没被 log。这个测试用来防止回归，验证 interceptor 会：
 *   1. 正常抛出带上游 body 的 HttpClientErrorException，
 *   2. 把 body 存进 AiSyncResponseCapture（TaskPlanner 的 fallback 会用到），
 *   3. 把 body 转发给 AiInteractionLogger，
 *   4. 在 [AI HTTP] logger 上以 ERROR 级别输出 body。
 */
class AiConfigErrorLoggingTest {

    private static final String ERROR_BODY =
            "{\"error\":{\"code\":\"400\",\"message\":\"Param Incorrect\",\"param\":\"Not supported model X\"}}";

    private HttpServer server;
    private int port;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger aiConfigLogger;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = ERROR_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        port = server.getAddress().getPort();
        server.start();

        aiConfigLogger = (Logger) LoggerFactory.getLogger(AiConfig.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        aiConfigLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (logAppender != null) {
            aiConfigLogger.detachAppender(logAppender);
        }
        AiSyncResponseCapture.clear();
        AiInteractionContext.clear();
    }

    @Test
    void interceptor_shouldSurfaceUpstream4xxBody_insteadOfSwallowingIt() {
        AiInteractionLogger interactionLogger = mock(AiInteractionLogger.class);
        AiConfig config = new AiConfig();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        RestClient client = config.openAiRestClientBuilder(interactionLogger, registry)
                .baseUrl("http://127.0.0.1:" + port)
                .build();

        assertThatThrownBy(() -> client.post()
                .uri("/v1/chat/completions")
                .body("{\"model\":\"X\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .retrieve()
                .toEntity(String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .hasMessageContaining("Not supported model X");

        // body 必须被捕获，TaskPlanner 提取 reasoning 的 fallback 路径要用。
        assertThat(AiSyncResponseCapture.get()).contains("Not supported model X");

        // AiInteractionLogger 收到的是完整的上游 body，不是占位符。
        verify(interactionLogger).logResponse(any(), eq(400), contains("Not supported model X"), anyLong());

        // 上游 body 以 ERROR 级别 log 出来，运维不用开 DEBUG 也能看到。
        boolean errorLogged = logAppender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("Not supported model X")
                        && e.getFormattedMessage().contains("[AI HTTP] <-- ERROR"));
        assertThat(errorLogged)
                .as("expected an ERROR log line containing the upstream 4xx body")
                .isTrue();

        // 400（客户端/配置错误）绝不能算作 circuit breaker 的失败。
        CircuitBreaker.Metrics m = registry.circuitBreaker("llmChat").getMetrics();
        assertThat(m.getNumberOfFailedCalls()).isZero();
        assertThat(m.getNumberOfSuccessfulCalls()).isEqualTo(1);
    }
}
