package com.dawn.ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryRewriterTest {

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;

    private QueryRewriter queryRewriter;

    @BeforeEach
    void setUp() {
        queryRewriter = new QueryRewriter(chatClient);
    }

    @Test
    @DisplayName("rewrite: queryRewriteEnabled=false 时直接返回原始查询，不调用 LLM")
    void rewrite_disabled_returnsOriginalQuery() {
        queryRewriter.setQueryRewriteEnabled(false);

        String result = queryRewriter.rewrite("月费多少");

        assertThat(result).isEqualTo("月费多少");
        verify(chatClient, never()).prompt();
    }

    @Test
    @DisplayName("rewrite: queryRewriteEnabled=true 时调用 LLM 并返回改写后的查询")
    void rewrite_enabled_returnsRewrittenQuery() {
        queryRewriter.setQueryRewriteEnabled(true);

        String llmResponse = "{\"rewrittenQuery\": \"Dawn AI 定价 月费 价格\"}";
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(llmResponse);

        String result = queryRewriter.rewrite("月费多少");

        assertThat(result).isEqualTo("Dawn AI 定价 月费 价格");
        verify(chatClient).prompt();
    }

    @Test
    @DisplayName("rewrite: 改写时将原始查询传给 LLM 的 user prompt")
    void rewrite_passesOriginalQueryToLlm() {
        queryRewriter.setQueryRewriteEnabled(true);

        String llmResponse = "{\"rewrittenQuery\": \"some query\"}";
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(llmResponse);

        queryRewriter.rewrite("原始查询内容");

        verify(requestSpec).user("原始查询内容");
    }
}
