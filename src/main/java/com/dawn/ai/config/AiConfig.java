package com.dawn.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);
    private static final int MAX_CONTENT_SNIPPET = 300;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${app.ai.system-prompt:You are a helpful AI assistant.}")
    private String defaultSystemPrompt;

    @Value("${spring.ai.openai.base-url:}")
    private String openAiBaseUrl;

    @Value("${spring.ai.openai.chat.base-url:}")
    private String chatBaseUrl;

    @Value("${spring.ai.openai.embedding.base-url:}")
    private String embeddingBaseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

    @Value("${spring.ai.openai.embedding.api-key:}")
    private String embeddingApiKey;

    @Value("${spring.ai.openai.embedding.options.model:}")
    private String embeddingModel;

    @Value("${spring.ai.openai.embedding.options.dimensions:}")
    private String embeddingDimensions;

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem(defaultSystemPrompt)
                .build();
    }

    @Bean
    @Primary
    public RestClient.Builder openAiRestClientBuilder(AiInteractionLogger aiInteractionLogger) {
        ClientHttpRequestInterceptor loggingInterceptor = (request, body, execution) -> {
            String reqBodyText = new String(body, StandardCharsets.UTF_8);
            String sessionId = AiInteractionContext.getSessionId();

            log.info("[AI HTTP] --> {} {} | {}", request.getMethod(), request.getURI(), summarizeRequestBody(reqBodyText));
            aiInteractionLogger.logRequest(sessionId, request.getMethod().name(), request.getURI().toString(), reqBodyText);

            long start = System.currentTimeMillis();
            ClientHttpResponse response = execution.execute(request, body);
            long latency = System.currentTimeMillis() - start;

            byte[] responseBody = StreamUtils.copyToByteArray(response.getBody());
            String responseBodyText = new String(responseBody, resolveCharset(response.getHeaders()));
            AiSyncResponseCapture.set(responseBodyText);

            if (response.getStatusCode().isError()) {
                // Failure path: dump full request + response so the upstream rejection reason is
                // visible in logs instead of being swallowed inside the IOException wrapper.
                log.error("[AI HTTP] <-- ERROR status={} latencyMs={} url={} {}\nrequestBody={}\nresponseBody={}",
                        response.getStatusCode(),
                        latency,
                        request.getMethod(),
                        request.getURI(),
                        reqBodyText,
                        responseBodyText);
            } else {
                log.info("[AI HTTP] <-- status={} | {}", response.getStatusCode(), summarizeResponseBody(responseBodyText));
                if (log.isDebugEnabled()) {
                    log.debug("[AI HTTP] <-- response body detail:\n{}", formatDebugResponseBody(responseBodyText));
                }
            }
            aiInteractionLogger.logResponse(sessionId, response.getStatusCode().value(), responseBodyText, latency);

            return response;
        };

        return RestClient.builder()
                .requestFactory(new BufferingClientHttpRequestFactory(new JdkClientHttpRequestFactory()))
                .requestInterceptor(loggingInterceptor);
    }

    @Bean
    @Primary
    public WebClient.Builder openAiWebClientBuilder(AiInteractionLogger aiInteractionLogger) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        return WebClient.builder()
                .exchangeStrategies(strategies)
                .filter(logStreamingRequest(aiInteractionLogger))
                .filter(logStreamingResponse(aiInteractionLogger));
    }

    @Bean
    public Timer aiCallTimer(MeterRegistry registry) {
        return Timer.builder("ai.chat.request.duration")
                .description("Duration of AI chat requests")
                .tag("model", "openai")
                .register(registry);
    }

    @Bean
    public ApplicationRunner aiStartupLogRunner() {
        return args -> {
            log.info("[AI Config] base-url={}, chat-base-url={}, embedding-base-url={}, api-key={}, embedding-api-key={}, embedding-model={}, embedding-dimensions={}",
                    openAiBaseUrl,
                    chatBaseUrl,
                    embeddingBaseUrl,
                    maskApiKey(openAiApiKey),
                    maskApiKey(embeddingApiKey),
                    embeddingModel,
                    embeddingDimensions);
            warnIfVersionSuffix("base-url", openAiBaseUrl);
            warnIfVersionSuffix("chat-base-url", chatBaseUrl);
            warnIfVersionSuffix("embedding-base-url", embeddingBaseUrl);
        };
    }

    // --- request/response summarizers ---

    private String summarizeRequestBody(String body) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            StringBuilder sb = new StringBuilder();
            if (root.has("model")) {
                sb.append("model=").append(root.get("model").asText());
            }
            if (root.has("messages")) {
                JsonNode messages = root.get("messages");
                sb.append(", messages=").append(messages.size());
                // Show the last user turn briefly so we know what was sent
                for (int i = messages.size() - 1; i >= 0; i--) {
                    JsonNode msg = messages.get(i);
                    if ("user".equals(msg.path("role").asText())) {
                        sb.append(", lastUser=「").append(snippet(msg.path("content").asText())).append("」");
                        break;
                    }
                }
            }
            if (root.has("input")) {
                JsonNode input = root.get("input");
                sb.append(", inputs=").append(input.isArray() ? input.size() : 1);
                String firstInput = extractFirstInputSnippet(input);
                if (!firstInput.isBlank()) {
                    sb.append(", firstInput=「").append(firstInput).append("」");
                }
            }
            if (root.has("tools")) {
                sb.append(", tools=").append(root.get("tools").size());
            }
            if (root.has("temperature")) {
                sb.append(", temperature=").append(root.get("temperature").asDouble());
            }
            if (root.has("stream")) {
                sb.append(", stream=").append(root.get("stream").asBoolean());
            }
            return sb.toString();
        } catch (Exception e) {
            return snippet(body);
        }
    }

    private String summarizeResponseBody(String body) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            StringBuilder sb = new StringBuilder();
            appendEmbeddingSummary(sb, root);
            if (root.has("choices") && root.get("choices").size() > 0) {
                JsonNode choice = root.get("choices").get(0);
                sb.append("finishReason=").append(choice.path("finish_reason").asText("?"));
                String content = choice.path("message").path("content").asText("");
                if (!content.isBlank()) {
                    sb.append(", content=「").append(snippet(content)).append("」");
                }
                JsonNode toolCalls = choice.path("message").path("tool_calls");
                if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                    sb.append(", toolCalls=[");
                    for (int i = 0; i < toolCalls.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(toolCalls.get(i).path("function").path("name").asText("?"));
                    }
                    sb.append("]");
                }
            }
            if (root.has("usage")) {
                JsonNode usage = root.get("usage");
                if (!sb.isEmpty()) sb.append(", ");
                appendUsageSummary(sb, usage);
            }
            if (root.has("error")) {
                sb.append(", error=").append(root.get("error").path("message").asText());
            }
            return sb.isEmpty() ? snippet(body) : sb.toString();
        } catch (Exception e) {
            return snippet(body);
        }
    }

    private String formatDebugResponseBody(String body) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            if (isEmbeddingResponse(root)) {
                return summarizeResponseBody(body) + ", embeddingValues=<omitted>";
            }
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception ignored) {
            return snippet(body);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ExchangeFilterFunction logStreamingRequest(AiInteractionLogger aiInteractionLogger) {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            log.info("[AI STREAM HTTP] --> {} {} | headers={}",
                    request.method(), request.url(), sanitizeHeaders(request));
            String sessionId = AiInteractionContext.getSessionId();
            String url = request.url().toString();
            String method = request.method().name();
            BodyInserter<?, ? super ClientHttpRequest> originalInserter = request.body();

            BodyInserter wrappedInserter = (BodyInserter<Object, ClientHttpRequest>) (message, context) -> {
                BodyCapturingHttpRequest capturing = new BodyCapturingHttpRequest(message);
                return ((BodyInserter) originalInserter).insert(capturing, context)
                        .doFinally(signal -> {
                            try {
                                aiInteractionLogger.logRequest(sessionId, method, url, capturing.getCapturedBody());
                            } catch (Exception ex) {
                                log.warn("[AI STREAM HTTP] failed to log captured request body: {}", ex.getMessage());
                            }
                        });
            };

            return Mono.just(ClientRequest.from(request).body(wrappedInserter).build());
        });
    }

    private ExchangeFilterFunction logStreamingResponse(AiInteractionLogger aiInteractionLogger) {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            log.info("[AI STREAM HTTP] <-- status={} | contentType={}",
                    response.statusCode(), response.headers().contentType().orElse(null));
            // SSE response body is too large/streamy to capture fully here.
            // ChatService writes a LOGICAL response with the aggregated answer when streaming completes.
            return Mono.just(response);
        });
    }

    /**
     * Reactive client request wrapper that buffers the outgoing body bytes so we can
     * record them to the AI interaction log. Uses {@link DataBufferUtils#join} to safely
     * combine all chunks into a single buffer regardless of underlying DataBuffer impl.
     */
    private static final class BodyCapturingHttpRequest extends ClientHttpRequestDecorator {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        BodyCapturingHttpRequest(ClientHttpRequest delegate) {
            super(delegate);
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(Flux.from(body)).flatMap(joined -> {
                copyToBuffer(joined);
                return super.writeWith(Mono.just(joined));
            });
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return DataBufferUtils.join(Flux.from(body).flatMap(Flux::from)).flatMap(joined -> {
                copyToBuffer(joined);
                return super.writeWith(Mono.just(joined));
            });
        }

        private void copyToBuffer(DataBuffer dataBuffer) {
            try {
                int n = dataBuffer.readableByteCount();
                if (n <= 0) return;
                byte[] copy = new byte[n];
                ByteBuffer view = dataBuffer.toByteBuffer();
                view.get(copy);
                buffer.write(copy, 0, n);
            } catch (Exception ignored) {
            }
        }

        String getCapturedBody() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private String snippet(String value) {
        if (value == null || value.length() <= MAX_CONTENT_SNIPPET) return value;
        return value.substring(0, MAX_CONTENT_SNIPPET) + "…";
    }

    private String extractFirstInputSnippet(JsonNode input) {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return "";
        }
        if (input.isTextual()) {
            return snippet(input.asText());
        }
        if (input.isArray() && !input.isEmpty()) {
            JsonNode first = input.get(0);
            if (first.isTextual()) {
                return snippet(first.asText());
            }
            return snippet(first.toString());
        }
        return snippet(input.toString());
    }

    private void appendEmbeddingSummary(StringBuilder sb, JsonNode root) {
        if (!isEmbeddingResponse(root)) {
            return;
        }
        JsonNode data = root.path("data");
        if (!sb.isEmpty()) {
            sb.append(", ");
        }
        sb.append("embeddings=").append(data.size());
        JsonNode firstEmbedding = data.isEmpty() ? null : data.get(0).path("embedding");
        if (firstEmbedding != null && firstEmbedding.isArray()) {
            sb.append(", dimensions=").append(firstEmbedding.size());
        }
        if (root.has("model")) {
            sb.append(", model=").append(root.path("model").asText());
        }
    }

    private void appendUsageSummary(StringBuilder sb, JsonNode usage) {
        boolean appended = false;
        if (usage.has("prompt_tokens")) {
            sb.append("promptTokens=").append(usage.path("prompt_tokens").asInt());
            appended = true;
        }
        if (usage.has("completion_tokens")) {
            if (appended) {
                sb.append(", ");
            }
            sb.append("completionTokens=").append(usage.path("completion_tokens").asInt());
            appended = true;
        }
        if (usage.has("total_tokens")) {
            if (appended) {
                sb.append(", ");
            }
            sb.append("totalTokens=").append(usage.path("total_tokens").asInt());
        }
    }

    private boolean isEmbeddingResponse(JsonNode root) {
        JsonNode data = root.path("data");
        return data.isArray() && !data.isEmpty() && data.get(0).has("embedding");
    }

    private HttpHeaders sanitizeHeaders(ClientRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(request.headers());
        if (headers.containsKey(HttpHeaders.AUTHORIZATION)) {
            headers.set(HttpHeaders.AUTHORIZATION, "<masked>");
        }
        return headers;
    }

    // --- helpers ---

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "<empty>";
        }
        if (apiKey.length() <= 8) {
            return "***" + apiKey.length();
        }
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4)
                + " (len=" + apiKey.length() + ")";
    }

    private Charset resolveCharset(HttpHeaders headers) {
        if (headers.getContentType() != null && headers.getContentType().getCharset() != null) {
            return headers.getContentType().getCharset();
        }
        return StandardCharsets.UTF_8;
    }

    private void warnIfVersionSuffix(String propertyName, String value) {
        if (value != null && value.endsWith("/v1")) {
            log.warn("[AI Config] {} ends with /v1. Spring AI appends endpoint paths automatically, which can produce duplicated /v1 segments for OpenAI-compatible providers.", propertyName);
        }
    }
}
