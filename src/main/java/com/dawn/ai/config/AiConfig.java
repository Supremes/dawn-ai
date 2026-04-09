package com.dawn.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);
    private static final int MAX_CONTENT_SNIPPET = 150;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${app.ai.system-prompt:You are a helpful AI assistant.}")
    private String defaultSystemPrompt;

    @Value("${spring.ai.openai.base-url:}")
    private String openAiBaseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

    @Value("${spring.ai.openai.embedding.options.model:}")
    private String embeddingModel;

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem(defaultSystemPrompt)
                .build();
    }

    @Bean
    @Primary
    public RestClient.Builder openAiRestClientBuilder() {
        ClientHttpRequestInterceptor loggingInterceptor = (request, body, execution) -> {
            String reqBodyText = new String(body, StandardCharsets.UTF_8);
            log.info("[AI HTTP] --> {} {} | {}", request.getMethod(), request.getURI(), summarizeRequestBody(reqBodyText));

            ClientHttpResponse response = execution.execute(request, body);

            byte[] responseBody = StreamUtils.copyToByteArray(response.getBody());
            String responseBodyText = new String(responseBody, resolveCharset(response.getHeaders()));

            log.info("[AI HTTP] <-- status={} | {}", response.getStatusCode(), summarizeResponseBody(responseBodyText));

            return response;
        };

        return RestClient.builder()
                .requestFactory(new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()))
                .requestInterceptor(loggingInterceptor);
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
            log.info("[AI Config] base-url={}, api-key={}, embedding-model={}",
                    openAiBaseUrl,
                    maskApiKey(openAiApiKey),
                    embeddingModel);
            if (openAiBaseUrl != null && openAiBaseUrl.endsWith("/v1")) {
                log.warn("[AI Config] base-url ends with /v1. Spring AI will append /v1/chat/completions automatically, which can produce a duplicated /v1 path for OpenAI-compatible providers.");
            }
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
                sb.append(", promptTokens=").append(usage.path("prompt_tokens").asInt())
                  .append(", completionTokens=").append(usage.path("completion_tokens").asInt());
            }
            if (root.has("error")) {
                sb.append(", error=").append(root.get("error").path("message").asText());
            }
            return sb.isEmpty() ? snippet(body) : sb.toString();
        } catch (Exception e) {
            return snippet(body);
        }
    }

    private String snippet(String value) {
        if (value == null || value.length() <= MAX_CONTENT_SNIPPET) return value;
        return value.substring(0, MAX_CONTENT_SNIPPET) + "…";
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

    private HttpHeaders sanitizeHeaders(HttpHeaders headers) {
        HttpHeaders sanitized = new HttpHeaders();
        headers.forEach((name, values) -> {
            if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
                sanitized.add(name, maskAuthorization(values.isEmpty() ? "" : values.get(0)));
                return;
            }
            sanitized.put(name, values);
        });
        return sanitized;
    }

    private String maskAuthorization(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return "<empty>";
        }

        int separatorIndex = authorization.indexOf(' ');
        if (separatorIndex < 0 || separatorIndex == authorization.length() - 1) {
            return "***";
        }

        return authorization.substring(0, separatorIndex + 1)
                + maskApiKey(authorization.substring(separatorIndex + 1));
    }
}
