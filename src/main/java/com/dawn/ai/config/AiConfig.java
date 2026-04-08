package com.dawn.ai.config;

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
    private static final int MAX_LOG_BODY_LENGTH = 4000;

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
            log.info("[AI HTTP] {} {}", request.getMethod(), request.getURI());
            log.info("[AI HTTP] Request headers={}", sanitizeHeaders(request.getHeaders()));
            log.info("[AI HTTP] Request body={}", abbreviate(new String(body, StandardCharsets.UTF_8)));
            ClientHttpResponse response = execution.execute(request, body);

            byte[] responseBody = StreamUtils.copyToByteArray(response.getBody());
            Charset responseCharset = resolveCharset(response.getHeaders());
            String responseBodyText = new String(responseBody, responseCharset);

            log.info("[AI HTTP] Response status={} contentType={} contentLength={}",
                    response.getStatusCode(),
                    response.getHeaders().getContentType(),
                    responseBody.length);
            log.info("[AI HTTP] Response body={}", abbreviate(responseBodyText));

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

    private String abbreviate(String value) {
        if (value == null || value.length() <= MAX_LOG_BODY_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LOG_BODY_LENGTH) + "...(truncated)";
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
