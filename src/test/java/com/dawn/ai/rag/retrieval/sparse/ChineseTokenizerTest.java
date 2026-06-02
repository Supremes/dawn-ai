package com.dawn.ai.rag.retrieval.sparse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChineseTokenizerTest {

    private ChineseTokenizer tokenizer;

    @BeforeEach
    void setUp() {
        tokenizer = new ChineseTokenizer();
    }

    @Test
    @DisplayName("中文分词：产生非空分词结果")
    void tokenize_chineseSegmentsCorrectly() {
        List<String> tokens = tokenizer.tokenize("今天天气真好");
        assertThat(tokens).isNotEmpty();
        // jieba SEARCH mode produces segments; verify it produces meaningful tokens
        assertThat(tokens).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("中英混合：英文单词保留并小写")
    void tokenize_mixedLanguage_lowercasesEnglish() {
        List<String> tokens = tokenizer.tokenize("Spring Boot 是一个框架");
        assertThat(tokens).contains("spring", "boot");
        assertThat(tokens).anyMatch(t -> t.equals("框架"));
    }

    @Test
    @DisplayName("空/null 输入返回空列表")
    void tokenize_nullAndBlank_returnsEmpty() {
        assertThat(tokenizer.tokenize(null)).isEmpty();
        assertThat(tokenizer.tokenize("")).isEmpty();
        assertThat(tokenizer.tokenize("   ")).isEmpty();
    }

    @Test
    @DisplayName("纯英文文本正常分词")
    void tokenize_englishText_works() {
        List<String> tokens = tokenizer.tokenize("Hello World Java Programming");
        assertThat(tokens).contains("hello", "world", "java", "programming");
    }

    @Test
    @DisplayName("标点符号被过滤")
    void tokenize_punctuationFiltered() {
        List<String> tokens = tokenizer.tokenize("你好，世界！Hello.");
        // Punctuation should be filtered out
        assertThat(tokens).noneMatch(t -> t.equals("，") || t.equals("！") || t.equals("."));
        assertThat(tokens).contains("你好", "世界");
    }
}
