package com.dawn.ai.rag.query;

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

    private static final String SYSTEM_PROMPT = """
            你是一个领域专家。请基于用户的问题，写一段简短、客观、信息密集的回答（80-150字），
            就像你正在引用一份真实的参考文档。
            要求：
            1. 直接陈述事实，不要使用"我认为"、"可能"等模糊语气。
            2. 只输出回答正文，不要前缀、不要标题、不要 markdown 格式。
            3. 即使你不确定答案，也请写出最合理的描述——这段文本仅用于向量检索，不会作为最终答案。
            """;

    private final ChatClient chatClient;

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
                    .system(SYSTEM_PROMPT)
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
