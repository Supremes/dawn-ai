package com.dawn.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigTest {

    private final AiConfig aiConfig = new AiConfig();

    @Test
    void summarizeResponseBody_shouldCompressEmbeddingResponses() {
        String body = """
                {
                  "object": "list",
                  "data": [
                    { "object": "embedding", "index": 0, "embedding": [0.1, 0.2, 0.3] },
                    { "object": "embedding", "index": 1, "embedding": [0.4, 0.5, 0.6] }
                  ],
                  "model": "bge-m3",
                  "usage": {
                    "prompt_tokens": 12,
                    "total_tokens": 12
                  }
                }
                """;

        String summary = ReflectionTestUtils.invokeMethod(aiConfig, "summarizeResponseBody", body);

        assertThat(summary)
                .contains("embeddings=2")
                .contains("dimensions=3")
                .contains("model=bge-m3")
                .contains("promptTokens=12")
                .contains("totalTokens=12");
        assertThat(summary).doesNotContain("completionTokens=").doesNotContain("0.1").doesNotContain("[");
    }

    @Test
    void formatDebugResponseBody_shouldOmitEmbeddingValues() {
        String body = """
                {
                  "object": "list",
                  "data": [
                    { "object": "embedding", "index": 0, "embedding": [0.123, 0.456] }
                  ],
                  "model": "bge-m3"
                }
                """;

        String detail = ReflectionTestUtils.invokeMethod(aiConfig, "formatDebugResponseBody", body);

        assertThat(detail)
                .contains("embeddings=1")
                .contains("dimensions=2")
                .contains("embeddingValues=<omitted>");
        assertThat(detail).doesNotContain("0.123").doesNotContain("\"embedding\"");
    }

    @Test
    void summarizeRequestBody_shouldIncludeEmbeddingInputSummary() {
        String body = """
                {
                  "model": "bge-m3",
                  "input": [
                    "第一段文本",
                    "第二段文本"
                  ]
                }
                """;

        String summary = ReflectionTestUtils.invokeMethod(aiConfig, "summarizeRequestBody", body);

        assertThat(summary)
                .contains("model=bge-m3")
                .contains("inputs=2")
                .contains("firstInput=「第一段文本」");
    }
}
