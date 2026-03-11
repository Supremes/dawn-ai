package com.dawn.ai.config;

import com.dawn.ai.exception.AiConfigurationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiAvailabilityChecker {

    private static final String PLACEHOLDER_API_KEY = "your-api-key-here";

    private final String openAiApiKey;

    public AiAvailabilityChecker(@Value("${spring.ai.openai.api-key:}") String openAiApiKey) {
        this.openAiApiKey = openAiApiKey;
    }

    public void ensureConfigured() {
        if (openAiApiKey == null || openAiApiKey.isBlank() || PLACEHOLDER_API_KEY.equals(openAiApiKey)) {
            throw new AiConfigurationException("OpenAI API key is not configured. Set OPENAI_API_KEY before calling AI or RAG endpoints.");
        }
    }
}