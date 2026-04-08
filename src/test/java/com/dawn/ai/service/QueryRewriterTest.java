package com.dawn.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueryRewriterTest {

    @Mock private OpenAIChatModel agentScopeModel;

    private QueryRewriter queryRewriter;

    @BeforeEach
    void setUp() {
        queryRewriter = new QueryRewriter(agentScopeModel, new ObjectMapper());
    }

    @Test
    @DisplayName("rewrite: queryRewriteEnabled=false 时直接返回原始查询，不调用 LLM")
    void rewrite_disabled_returnsOriginalQuery() {
        queryRewriter.setQueryRewriteEnabled(false);

        String result = queryRewriter.rewrite("月费多少");

        assertThat(result).isEqualTo("月费多少");
        verify(agentScopeModel, never()).stream(anyList(), anyList(), any());
    }

    @Test
    @DisplayName("rewrite: queryRewriteEnabled=true 时调用 LLM 并返回改写后的查询")
    void rewrite_enabled_returnsRewrittenQuery() {
        queryRewriter.setQueryRewriteEnabled(true);
        mockModelResponse("{\"rewrittenQuery\": \"Dawn AI 定价 月费 价格\"}");

        String result = queryRewriter.rewrite("月费多少");

        assertThat(result).isEqualTo("Dawn AI 定价 月费 价格");
        verify(agentScopeModel).stream(anyList(), eq(Collections.emptyList()), any(GenerateOptions.class));
    }

    @Test
    @DisplayName("LLM 返回空时降级返回原始查询")
    void rewrite_blankRewrittenQuery_returnsOriginalQuery() {
        queryRewriter.setQueryRewriteEnabled(true);
        mockModelResponse("{\"rewrittenQuery\": \"\"}");

        String result = queryRewriter.rewrite("月费多少");

        assertThat(result).isEqualTo("月费多少");
    }

    @Test
    @DisplayName("LLM 调用抛出异常时降级返回原始查询")
    void rewrite_llmThrowsException_returnsOriginalQuery() {
        queryRewriter.setQueryRewriteEnabled(true);
        when(agentScopeModel.stream(anyList(), anyList(), any()))
                .thenThrow(new RuntimeException("LLM unavailable"));

        String result = queryRewriter.rewrite("月费多少");

        assertThat(result).isEqualTo("月费多少");
    }

    @Test
    @DisplayName("LLM 返回非 JSON 文本时直接使用该文本作为改写结果")
    void rewrite_nonJsonResponse_usesRawText() {
        queryRewriter.setQueryRewriteEnabled(true);
        mockModelResponse("Dawn AI 定价 月费");

        String result = queryRewriter.rewrite("月费多少");

        assertThat(result).isEqualTo("Dawn AI 定价 月费");
    }

    @Test
    @DisplayName("流式分片响应会拼接全部文本，而不是只取最后一个空分片")
    void rewrite_streamedChunks_concatenatesAllFragments() {
        queryRewriter.setQueryRewriteEnabled(true);

        ChatResponse first = chatResponse("{\"rewritten");
        ChatResponse second = chatResponse("Query\": \"杜康\"}");
        ChatResponse last = ChatResponse.builder().content(List.of()).build();

        when(agentScopeModel.stream(anyList(), anyList(), any()))
                .thenReturn(Flux.just(first, second, last));

        String result = queryRewriter.rewrite("杜康");

        assertThat(result).isEqualTo("杜康");
    }

    private void mockModelResponse(String text) {
        when(agentScopeModel.stream(anyList(), anyList(), any()))
                .thenReturn(Flux.just(chatResponse(text)));
    }

    private ChatResponse chatResponse(String text) {
        List<ContentBlock> content = List.of(TextBlock.builder().text(text).build());
        return ChatResponse.builder()
                .content(content)
                .build();
    }
}
