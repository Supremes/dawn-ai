package com.dawn.ai.rag.query;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class QueryDomainClassifier {

    private final ChatClient chatClient;

    private final BeanOutputConverter<DomainResult> converter =
            new BeanOutputConverter<>(DomainResult.class);

    @Setter
    @Value("${app.ai.rag.domain-gate-enabled:false}")
    private boolean domainGateEnabled;

    @Value("${app.ai.rag.domain-gate-llm-enabled:false}")
    private boolean domainGateLlmEnabled;

    @Value("${app.ai.rag.domain-description:当前知识库}")
    private String domainDescription;

    @Value("${app.ai.rag.out-of-domain-keywords:}")
    private List<String> outOfDomainKeywords = List.of();

    private record DomainResult(Boolean inDomain, String reasoning) {}

    public boolean shouldRetrieve(String query) {
        if (!domainGateEnabled || query == null || query.isBlank()) {
            return true;
        }

        String matchedKeyword = matchedOutOfDomainKeyword(query);
        if (matchedKeyword != null) {
            log.info("[QueryDomainClassifier] Out-of-domain query blocked by keyword. query='{}', keyword='{}'",
                    query, matchedKeyword);
            return false;
        }

        if (!domainGateLlmEnabled) {
            return true;
        }

        try {
            String response = chatClient.prompt()
                    .system("""
                            你是 RAG 检索前的领域边界判定器。
                            知识库范围：%s

                            判断用户问题是否能由这个知识库回答。
                            - 如果问题属于知识库范围，返回 inDomain=true。
                            - 如果问题需要外部实时信息、生活娱乐、通用常识、消费电子、天气、体育、小说、烹饪，或只是词面相似但语义不属于知识库，返回 inDomain=false。
                            - 宁可保守放行边界内技术问题，不要因为关键词相似放行明显越界的问题。
                            %s
                            """.formatted(domainDescription, converter.getFormat()))
                    .user(query)
                    .options(OpenAiChatOptions.builder().temperature(0.0).build())
                    .call()
                    .content();

            DomainResult result = converter.convert(response);
            if (result == null || result.inDomain() == null) {
                log.warn("[QueryDomainClassifier] Invalid classification response, falling back to retrieval. query='{}', response='{}'",
                        query, response);
                return true;
            }

            if (!result.inDomain()) {
                log.info("[QueryDomainClassifier] Out-of-domain query blocked. query='{}', reason='{}'",
                        query, result.reasoning());
                return false;
            }

            log.debug("[QueryDomainClassifier] In-domain query allowed. query='{}', reason='{}'",
                    query, result.reasoning());
            return true;
        } catch (Exception e) {
            log.warn("[QueryDomainClassifier] Classification failed, falling back to retrieval. query='{}', error={}",
                    query, e.getMessage());
            return true;
        }
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
}
