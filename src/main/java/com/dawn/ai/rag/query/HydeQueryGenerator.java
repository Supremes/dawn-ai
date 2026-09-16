package com.dawn.ai.rag.query;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * HyDE (Hypothetical Document Embeddings) — Gao et al. 2022。
 *
 * 先让 LLM 写一小段 *回答* 用户问题的假设性文本，再拿这段文本去做检索。
 * 这段假设文本通常和真实相关文档共享词汇、在 embedding 空间里也挨得近，
 * 所以能提升 zero-shot 稠密检索的召回率。
 *
 * 在 pipeline 里的位置：
 *   原始 query → QueryRewriter（关键词清洗）→ HydeQueryGenerator → 检索
 *
 * 失败行为：LLM 出任何错都 fallback 到输入 query（不会往外抛异常）。
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
     * 为 query 生成假设文档。功能关闭或出任何错时，原样返回输入的 query。
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
