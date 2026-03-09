package com.dawn.ai.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Value("${app.ai.system-prompt:You are a helpful AI assistant.}")
    private String defaultSystemPrompt;

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
}
