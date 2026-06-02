package com.dawn.ai.rag.query;

import com.dawn.ai.config.PromptManager;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HydeQueryGeneratorTest {

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;
    @Mock private PromptManager promptManager;

    private HydeQueryGenerator generator;

    @BeforeEach
    void setUp() {
        lenient().when(promptManager.render(anyString())).thenReturn("你是一个领域专家。");
        generator = new HydeQueryGenerator(chatClient, promptManager);
    }

    @Test
    @DisplayName("disabled 时不调用 LLM，原样返回输入")
    void generate_disabled_returnsOriginalQuery() {
        generator.setHydeEnabled(false);

        String result = generator.generate("如何配置 Dawn AI 的检索阈值");

        assertThat(result).isEqualTo("如何配置 Dawn AI 的检索阈值");
        verify(chatClient, never()).prompt();
    }

    @Test
    @DisplayName("enabled 时返回 LLM 生成的假设文档（已 trim）")
    void generate_enabled_returnsHypotheticalDoc() {
        generator.setHydeEnabled(true);
        stubChatClient("  Dawn AI 的检索阈值通过 application.yml 中的 app.ai.rag.similarity-threshold 配置，默认 0.6。  ");

        String result = generator.generate("如何配置 Dawn AI 的检索阈值");

        assertThat(result).isEqualTo(
                "Dawn AI 的检索阈值通过 application.yml 中的 app.ai.rag.similarity-threshold 配置，默认 0.6。");
        verify(requestSpec).options(any());
    }

    @Test
    @DisplayName("传给 LLM user prompt 的应是输入 query")
    void generate_passesInputQueryToUser() {
        generator.setHydeEnabled(true);
        stubChatClient("hypothetical answer");

        generator.generate("某个具体问题");

        verify(requestSpec).user("某个具体问题");
    }

    @Test
    @DisplayName("LLM 返回空白时降级为原始查询")
    void generate_blankResponse_returnsOriginalQuery() {
        generator.setHydeEnabled(true);
        stubChatClient("   ");

        String result = generator.generate("query");

        assertThat(result).isEqualTo("query");
    }

    @Test
    @DisplayName("LLM 抛异常时降级为原始查询，不向外传播")
    void generate_llmThrows_returnsOriginalQuery() {
        generator.setHydeEnabled(true);
        when(chatClient.prompt()).thenThrow(new RuntimeException("LLM unavailable"));

        String result = generator.generate("query");

        assertThat(result).isEqualTo("query");
    }

    @Test
    @DisplayName("空白输入直接返回，不调用 LLM")
    void generate_blankInput_returnsInputUnchanged() {
        generator.setHydeEnabled(true);

        assertThat(generator.generate("")).isEqualTo("");
        assertThat(generator.generate(null)).isNull();
        verify(chatClient, never()).prompt();
    }

    private void stubChatClient(String content) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(content);
    }
}
