package com.dawn.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class HydeQueryExpander {

    private final ChatClient chatClient;

    @Setter
    @Value("${app.ai.rag.hyde-enabled:true}")
    private boolean hydeEnabled;

    public String expand(String originalQuery) {
        if (!hydeEnabled) {
            return originalQuery;
        }

        try {
            String hypotheticalDocument = chatClient.prompt()
                    .system("You are generating a hypothetical document for semantic retrieval. "
                            + "Write a concise factual passage that would likely answer the user's question. "
                            + "Include concrete product terms, entities, and domain keywords when relevant. "
                            + "Do not mention that the passage is hypothetical. Do not use markdown.")
                    .user(originalQuery)
                    .options(OpenAiChatOptions.builder().temperature(0.3).build())
                    .call()
                    .content();

            if (hypotheticalDocument == null || hypotheticalDocument.isBlank()) {
                log.warn("[HydeQueryExpander] LLM returned blank hypothetical document, falling back to original query. query='{}'", originalQuery);
                return originalQuery;
            }

            String normalized = hypotheticalDocument.trim();
            log.debug("[HydeQueryExpander] query='{}' expanded for retrieval", originalQuery);
            return normalized;
        } catch (Exception e) {
            log.warn("[HydeQueryExpander] Failed to expand query, falling back to original. query='{}', error={}", originalQuery, e.getMessage());
            return originalQuery;
        }
    }
}