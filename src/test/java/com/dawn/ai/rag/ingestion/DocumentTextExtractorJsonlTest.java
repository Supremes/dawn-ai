package com.dawn.ai.rag.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTextExtractorJsonlTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void shouldExtractDefaultTextFieldPerLine() {
        String jsonl = """
                {"text": "first record"}
                {"text": "second record"}
                """;

        List<String> records = extractor.extractJsonlRecords(jsonl(jsonl), null);

        assertThat(records).containsExactly("first record", "second record");
    }

    @Test
    void shouldSkipBlankLines() {
        String jsonl = "{\"text\": \"a\"}\n\n   \n{\"text\": \"b\"}\n";

        List<String> records = extractor.extractJsonlRecords(jsonl(jsonl), null);

        assertThat(records).containsExactly("a", "b");
    }

    @Test
    void shouldSkipMalformedLinesButKeepValidOnes() {
        String jsonl = """
                {"text": "good one"}
                {not valid json}
                {"text": "good two"}
                """;

        List<String> records = extractor.extractJsonlRecords(jsonl(jsonl), null);

        assertThat(records).containsExactly("good one", "good two");
    }

    @Test
    void shouldSkipLinesMissingOrBlankTextField() {
        String jsonl = """
                {"text": "kept"}
                {"other": "no text field"}
                {"text": "   "}
                {"text": null}
                """;

        List<String> records = extractor.extractJsonlRecords(jsonl(jsonl), null);

        assertThat(records).containsExactly("kept");
    }

    @Test
    void shouldHonorCustomTextField() {
        String jsonl = """
                {"content": "body one", "text": "ignored"}
                {"content": "body two"}
                """;

        List<String> records = extractor.extractJsonlRecords(jsonl(jsonl), "content");

        assertThat(records).containsExactly("body one", "body two");
    }

    @Test
    void shouldThrowWhenNoValidRecordExists() {
        String jsonl = """
                {"other": "x"}
                {bad}
                """;

        assertThatThrownBy(() -> extractor.extractJsonlRecords(jsonl(jsonl), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No valid JSONL records");
    }

    private MockMultipartFile jsonl(String content) {
        return new MockMultipartFile(
                "file", "dataset.jsonl", "application/x-ndjson",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
