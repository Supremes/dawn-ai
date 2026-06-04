package com.dawn.ai.rag.query;

import com.dawn.ai.config.PromptManager;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * HyDE (Hypothetical Document Embeddings) — Gao et al. 2022.
 *
 * Generate a single short hypothetical passage that *answers* the user's query,
 * then use that passage as the retrieval input. The hypothetical text typically
 * shares vocabulary and embedding-space neighborhood with real relevant docs,
 * improving recall on zero-shot dense retrieval.
 *
 * Pipeline position:
 *   original query → QueryRewriter (keyword cleanup) → HydeQueryGenerator → retrieve
 *
 * Failure behavior: any LLM error → fall back to input query (no exception leaks out).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HydeQueryGenerator {

    private final ChatClient chatClient;
    private final PromptManager promptManager;

    @Setter
    @Value("${app.ai.rag.hyde-enabled:false}")
    private boolean hydeEnabled;

    /**
     * Generate hypothetical document for the query. Returns the input query unchanged
     * when disabled or on any error.
     */
    public String generate(String query) {
        if (!hydeEnabled || query == null || query.isBlank()) {
            return query;
        }

        try {
            String hypothetical = chatClient.prompt()
                    .system(promptManager.render("hyde-system"))
                    .user(query)
                    .options(OpenAiChatOptions.builder().temperature(0.3).build())
                    .call()
                    .content();

            if (hypothetical == null || hypothetical.isBlank()) {
                log.warn("[HydeQueryGenerator] LLM returned blank hypothetical doc, falling back to original. query='{}'", query);
                return query;
            }

            String trimmed = hypothetical.trim();
            log.debug("[HydeQueryGenerator] query='{}' → hypothetical='{}'", query, trimmed);
            return trimmed;
        } catch (Exception e) {
            log.warn("[HydeQueryGenerator] Failed to generate hypothetical doc, falling back to original. query='{}', error={}",
                    query, e.getMessage());
            return query;
        }
    }
}
