package com.dawn.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriter {

    private final OpenAIChatModel agentScopeModel;
    private final ObjectMapper objectMapper;

    @Setter
    @Value("${app.ai.rag.query-rewrite-enabled:true}")
    private boolean queryRewriteEnabled;

    private static final String SYSTEM_PROMPT =
            "将用户问题改写为适合向量检索的关键词短语，保留核心语义，去除口语助词。"
                    + "请以 JSON 格式返回，格式为: {\"rewrittenQuery\": \"改写后的查询\"}";

    public String rewrite(String originalQuery) {
        if (!queryRewriteEnabled) {
            return originalQuery;
        }

        try {
            Msg systemMsg = Msg.builder()
                    .role(MsgRole.SYSTEM)
                    .textContent(SYSTEM_PROMPT)
                    .build();

            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(originalQuery)
                    .build();

            GenerateOptions options = GenerateOptions.builder()
                    .temperature(0.1)
                    .build();

            String text = agentScopeModel
                    .stream(List.of(systemMsg, userMsg), Collections.emptyList(), options)
                    .map(this::extractText)
                    .filter(fragment -> fragment != null && !fragment.isBlank())
                    .collect(StringBuilder::new, (builder, fragment) -> builder.append(fragment))
                    .map(StringBuilder::toString)
                    .defaultIfEmpty("")
                    .block();

            if (text == null) {
                log.warn("[QueryRewriter] LLM returned null response, falling back to original. query='{}'", originalQuery);
                return originalQuery;
            }

            String rewritten = extractRewrittenQuery(text);

            if (rewritten == null || rewritten.isBlank()) {
                log.warn("[QueryRewriter] LLM returned blank rewrittenQuery, falling back to original. query='{}'", originalQuery);
                return originalQuery;
            }

            log.debug("[QueryRewriter] query='{}' → rewritten='{}'", originalQuery, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.warn("[QueryRewriter] Failed to rewrite query, falling back to original. query='{}', error={}", originalQuery, e.getMessage());
            return originalQuery;
        }
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return "";
        }

        return response.getContent().stream()
                .filter(TextBlock.class::isInstance)
                .map(c -> ((TextBlock) c).getText())
                .reduce(String::concat)
                .orElse("");
    }

    private String extractRewrittenQuery(String text) {
        try {
            JsonNode node = objectMapper.readTree(text);
            return node.path("rewrittenQuery").asText(null);
        } catch (Exception e) {
            log.debug("[QueryRewriter] Failed to parse JSON response, trying raw text. raw='{}'", text);
            return text.isBlank() ? null : text.trim();
        }
    }
}
