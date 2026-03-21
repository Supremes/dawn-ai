package com.dawn.ai.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Value("${app.ai.system-prompt:You are a helpful AI assistant.}")
    private String defaultSystemPrompt;

    @Value("${spring.ai.openai.base-url:}")
    private String openAiBaseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem(defaultSystemPrompt)
                .build();
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
            log.info("[AI Config] base-url={}, api-key={}",
                    openAiBaseUrl,
                    maskApiKey(openAiApiKey));
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
}
