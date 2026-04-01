package com.dawn.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriter {

    private final ChatClient chatClient;

    @Setter
    @Value("${app.ai.rag.query-rewrite-enabled:true}")
    private boolean queryRewriteEnabled;

    record RewriteResult(String rewrittenQuery) {}

    public String rewrite(String originalQuery) {
        if (!queryRewriteEnabled) {
            return originalQuery;
        }

        BeanOutputConverter<RewriteResult> converter =
                new BeanOutputConverter<>(RewriteResult.class);

        String response = chatClient.prompt()
                .system("将用户问题改写为适合向量检索的关键词短语，保留核心语义，去除口语助词。"
                        + converter.getFormat())
                .user(originalQuery)
                .options(OpenAiChatOptions.builder().temperature(0.1).build())
                .call()
                .content();

        return converter.convert(response).rewrittenQuery();
    }
}
