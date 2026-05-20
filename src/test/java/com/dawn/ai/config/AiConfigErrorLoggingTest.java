package com.dawn.ai.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
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
 * Regression test for the upstream 4xx visibility fix.
 *
 * Prior bug: openAiRestClientBuilder used SimpleClientHttpRequestFactory (JDK
 * HttpURLConnection). When upstream returned 4xx, getInputStream() threw IOException
 * and Spring wrapped it as ResourceAccessException — the actual error body from the
 * provider was never read or logged. This test guards against any regression by
 * verifying the interceptor:
 *   1. surfaces a normal HttpClientErrorException carrying the upstream body,
 *   2. captures the body into AiSyncResponseCapture (used by TaskPlanner fallback),
 *   3. forwards the body to AiInteractionLogger,
 *   4. emits the body at ERROR level on the [AI HTTP] logger.
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
        RestClient client = config.openAiRestClientBuilder(interactionLogger)
                .baseUrl("http://127.0.0.1:" + port)
                .build();

        assertThatThrownBy(() -> client.post()
                .uri("/v1/chat/completions")
                .body("{\"model\":\"X\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .retrieve()
                .toEntity(String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .hasMessageContaining("Not supported model X");

        // Body must be captured for the TaskPlanner reasoning-extraction fallback path.
        assertThat(AiSyncResponseCapture.get()).contains("Not supported model X");

        // AiInteractionLogger receives the full upstream body, not a placeholder.
        verify(interactionLogger).logResponse(any(), eq(400), contains("Not supported model X"), anyLong());

        // The upstream body is logged at ERROR level so operators can see it without DEBUG.
        boolean errorLogged = logAppender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("Not supported model X")
                        && e.getFormattedMessage().contains("[AI HTTP] <-- ERROR"));
        assertThat(errorLogged)
                .as("expected an ERROR log line containing the upstream 4xx body")
                .isTrue();
    }
}
