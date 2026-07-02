package com.dawn.ai.rag.query;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class QueryCategoryClassifier {

    private final ChatClient chatClient;
    private final BeanOutputConverter<ClassifyResult> converter =
            new BeanOutputConverter<>(ClassifyResult.class);

    private String systemPrompt;

    @Setter
    @Value("${app.ai.rag.category-classify-enabled:false}")
    private boolean categoryClassifyEnabled;

    @Getter
    @Value("${app.ai.rag.categories}")
    private List<String> categories = List.of();

    @Setter
    @Value("${app.ai.rag.domain-gate-enabled:false}")
    private boolean domainGateEnabled;

    @Value("${app.ai.rag.domain-description:当前知识库}")
    private String domainDescription;

    @Value("${app.ai.rag.out-of-domain-keywords:}")
    private List<String> outOfDomainKeywords = List.of();

    public record ClassifyResult(String category, Boolean inDomain) {}

    public QueryCategoryClassifier(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostConstruct
    void init() {
        this.systemPrompt = buildSystemPrompt();
    }

    public ClassifyResult classify(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }

        boolean bothDisabled = !categoryClassifyEnabled && !domainGateEnabled;
        if (bothDisabled) {
            return null;
        }

        if (domainGateEnabled) {
            String matchedKeyword = matchedOutOfDomainKeyword(query);
            if (matchedKeyword != null) {
                log.info("[QueryCategoryClassifier] Out-of-domain query blocked by keyword. query='{}', keyword='{}'",
                        query, matchedKeyword);
                return new ClassifyResult(null, false);
            }
        }

        if (!categoryClassifyEnabled && domainGateEnabled) {
            return null;
        }

        try {
            String response = chatClient.prompt()
                    .system(systemPrompt + converter.getFormat())
                    .user(query)
                    .options(OpenAiChatOptions.builder().temperature(0.1).build())
                    .call()
                    .content();

            ClassifyResult result = converter.convert(response);
            if (result == null) {
                log.debug("[QueryCategoryClassifier] Null result for query='{}', falling through", query);
                return null;
            }

            if (domainGateEnabled && Boolean.FALSE.equals(result.inDomain())) {
                log.info("[QueryCategoryClassifier] Out-of-domain query blocked. query='{}', category='{}'",
                        query, result.category());
                return new ClassifyResult(null, false);
            }

            String category = result.category();
            if (category == null || category.isBlank() || "null".equalsIgnoreCase(category)) {
                log.debug("[QueryCategoryClassifier] No confident category for query='{}', using full retrieval", query);
                return new ClassifyResult(null, resolveInDomain(result));
            }

            if (!categories.contains(category)) {
                log.warn("[QueryCategoryClassifier] LLM returned unknown category='{}', ignoring. query='{}'", category, query);
                return new ClassifyResult(null, resolveInDomain(result));
            }

            log.debug("[QueryCategoryClassifier] query='{}' → category='{}', inDomain={}", query, category, result.inDomain());
            return new ClassifyResult(category, resolveInDomain(result));
        } catch (Exception e) {
            log.warn("[QueryCategoryClassifier] Classification failed, falling back to full retrieval. query='{}', error={}",
                    query, e.getMessage());
            return null;
        }
    }

    private Boolean resolveInDomain(ClassifyResult result) {
        return domainGateEnabled ? result.inDomain() : null;
    }

    private String matchedOutOfDomainKeyword(String query) {
        if (outOfDomainKeywords == null || outOfDomainKeywords.isEmpty()) {
            return null;
        }

        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        return outOfDomainKeywords.stream()
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .map(String::trim)
                .filter(keyword -> normalizedQuery.contains(keyword.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(null);
    }

    private String buildSystemPrompt() {
        if (domainGateEnabled) {
            return """
                    你是 RAG 检索前的查询分类器。
                    知识库范围：%s
                    候选分类：%s

                    任务：
                    1. 判断用户问题是否属于知识库范围（inDomain）
                    2. 如果属于，从候选分类中选择最匹配的（category）

                    规则：
                    - 问题不属于知识库（天气、体育、娱乐、烹饪、消费电子、小说等）→ inDomain=false, category=null
                    - 属于知识库但无法确定分类 → inDomain=true, category=null
                    - 属于知识库且可确定分类 → inDomain=true, category=分类名
                    - 宁可保守放行边界内技术问题，不要因为关键词相似放行明显越界的问题
                    """.formatted(domainDescription, String.join("、", categories));
        }

        return "你是一个查询分类器。根据用户的查询内容，从以下候选分类中选择最匹配的一个：" +
                String.join("、", categories) +
                "。如果没有任何分类匹配，请返回 null。只返回分类名称，不要解释。";
    }
}
