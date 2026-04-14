package com.dawn.ai.rag.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OverlapTextSplitterTest {

    @Test
    @DisplayName("apply: 按 chunkSize 和 overlap 生成重叠 chunks")
    void apply_createsOverlappingChunks() {
        OverlapTextSplitter splitter = new OverlapTextSplitter(4, 2);
        Document document = new Document("doc-1", "one two three four five six seven", Map.of("source", "pricing"));

        List<Document> chunks = splitter.apply(List.of(document));

        assertThat(chunks).extracting(Document::getText)
                .containsExactly(
                        "one two three four",
                        "three four five six",
                        "five six seven"
                );
    }

    @Test
    @DisplayName("apply: chunk 继承父 metadata，并补齐 parentDocumentId 与 chunkIndex")
    void apply_preservesParentMetadataAndAddsChunkMetadata() {
        OverlapTextSplitter splitter = new OverlapTextSplitter(3, 1);
        Document document = new Document("doc-9", "alpha beta gamma delta epsilon", Map.of(
                "source", "kb",
                "category", "billing"
        ));

        List<Document> chunks = splitter.apply(List.of(document));

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getMetadata()).containsEntry("source", "kb");
            assertThat(chunk.getMetadata()).containsEntry("category", "billing");
            assertThat(chunk.getMetadata()).containsEntry("parentDocumentId", "doc-9");
            assertThat(chunk.getMetadata()).containsEntry("chunkCount", 2);
        });
        assertThat(chunks.get(0).getMetadata()).containsEntry("chunkIndex", 0);
        assertThat(chunks.get(1).getMetadata()).containsEntry("chunkIndex", 1);
    }
}
