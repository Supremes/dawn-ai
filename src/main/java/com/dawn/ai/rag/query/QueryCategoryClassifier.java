package com.dawn.ai.rag.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Slf4j
@Service
public class QueryCategoryClassifier {

    private final ChatClient chatClient;
    private final BeanOutputConverter<ClassifyResult> converter =
            new BeanOutputConverter<>(ClassifyResult.class);

    private final String systemPrompt;

    @Setter
    @Value("${app.ai.rag.category-classify-enabled:false}")
    private boolean categoryClassifyEnabled;

    @Getter
    @Value("${app.ai.rag.categories}")
    private List<String> categories = List.of();

    public QueryCategoryClassifier(ChatClient chatClient) {
        this.chatClient = chatClient;
        this.systemPrompt = buildSystemPrompt();
    }

    private record ClassifyResult(String category) {}

    public String classify(String query) {
        if (!categoryClassifyEnabled || query == null || query.isBlank()) {
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
            String category = (result != null) ? result.category() : null;

            if (category == null || category.isBlank() || "null".equalsIgnoreCase(category)) {
                log.debug("[QueryCategoryClassifier] No confident category for query='{}', using full retrieval", query);
                return null;
            }

            if (!categories.contains(category)) {
                log.warn("[QueryCategoryClassifier] LLM returned unknown category='{}', ignoring. query='{}'", category, query);
                return null;
            }

            log.debug("[QueryCategoryClassifier] query='{}' → category='{}'", query, category);
            return category;
        } catch (Exception e) {
            log.warn("[QueryCategoryClassifier] Classification failed, falling back to full retrieval. query='{}', error={}",
                    query, e.getMessage());
            return null;
        }
    }

    private String buildSystemPrompt() {
        return "你是一个查询分类器。根据用户的查询内容，从以下候选分类中选择最匹配的一个：" +
                categories +
                "。如果没有任何分类匹配，请返回 null。只返回分类名称，不要解释。";
    }
}
