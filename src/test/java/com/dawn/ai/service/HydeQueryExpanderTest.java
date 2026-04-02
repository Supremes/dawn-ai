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
class HydeQueryExpanderTest {

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;

    private HydeQueryExpander hydeQueryExpander;

    @BeforeEach
    void setUp() {
        hydeQueryExpander = new HydeQueryExpander(chatClient);
    }

    @Test
    @DisplayName("expand: hydeEnabled=false 时直接返回原始查询，不调用 LLM")
    void expand_disabled_returnsOriginalQuery() {
        hydeQueryExpander.setHydeEnabled(false);

        String result = hydeQueryExpander.expand("月费多少");

        assertThat(result).isEqualTo("月费多少");
        verify(chatClient, never()).prompt();
    }

    @Test
    @DisplayName("expand: hydeEnabled=true 时调用 LLM 生成检索文档")
    void expand_enabled_returnsHypotheticalDocument() {
        hydeQueryExpander.setHydeEnabled(true);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("Dawn AI 提供按月订阅方案，月费为 99 元，包含基础智能问答与知识库检索能力。   ");

        String result = hydeQueryExpander.expand("月费多少");

        assertThat(result).isEqualTo("Dawn AI 提供按月订阅方案，月费为 99 元，包含基础智能问答与知识库检索能力。");
        verify(requestSpec).user("月费多少");
    }

    @Test
    @DisplayName("expand: LLM 返回空时降级返回原始查询")
    void expand_blankResult_returnsOriginalQuery() {
        hydeQueryExpander.setHydeEnabled(true);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("   ");

        String result = hydeQueryExpander.expand("月费多少");

        assertThat(result).isEqualTo("月费多少");
    }

    @Test
    @DisplayName("expand: LLM 调用异常时降级返回原始查询")
    void expand_exception_returnsOriginalQuery() {
        hydeQueryExpander.setHydeEnabled(true);
        when(chatClient.prompt()).thenThrow(new RuntimeException("LLM unavailable"));

        String result = hydeQueryExpander.expand("月费多少");

        assertThat(result).isEqualTo("月费多少");
    }
}