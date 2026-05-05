package com.dawn.ai.rag.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OverlapTextSplitterTest {

    @Test
    @DisplayName("apply: 按 token 窗口切分并保留 overlap")
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
                "category", "billing",
                "docId", "doc-9"
        ));

        List<Document> chunks = splitter.apply(List.of(document));

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getMetadata()).containsEntry("source", "kb");
            assertThat(chunk.getMetadata()).containsEntry("category", "billing");
            assertThat(chunk.getMetadata()).containsEntry("docId", "doc-9");
            assertThat(chunk.getMetadata()).containsEntry("parentDocumentId", "doc-9");
            assertThat(chunk.getMetadata()).containsEntry("chunkCount", 2);
        });
        assertThat(chunks.get(0).getMetadata()).containsEntry("chunkIndex", 0);
        assertThat(chunks.get(1).getMetadata()).containsEntry("chunkIndex", 1);
    }

    @Test
    @DisplayName("apply: 多 chunk 文档生成 PGVector 兼容的 UUID chunk id")
    void apply_generatesUuidCompatibleChunkIdsForMultipleChunks() {
        OverlapTextSplitter splitter = new OverlapTextSplitter(4, 2);
        String parentId = UUID.randomUUID().toString();
        Document document = new Document(parentId, "one two three four five six seven", Map.of("docId", parentId));

        List<Document> chunks = splitter.apply(List.of(document));

        assertThat(chunks).hasSize(3);
        assertThat(chunks).extracting(Document::getId).doesNotHaveDuplicates();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(UUID.fromString(chunk.getId())).isNotNull();
            assertThat(chunk.getMetadata()).containsEntry("docId", parentId);
            assertThat(chunk.getMetadata()).containsEntry("parentDocumentId", parentId);
        });
    }

    @Test
    @DisplayName("apply: 优先在标点边界截断，避免句子中间硬切")
    void apply_prefersPunctuationBoundaries() {
        OverlapTextSplitter splitter = new OverlapTextSplitter(
                12,
                2);
        Document document = new Document(
                "doc-2",
                "alpha beta gamma delta epsilon. zeta eta theta iota kappa lambda mu nu xi omicron.",
                Map.of("source", "kb"));

        List<Document> chunks = splitter.apply(List.of(document));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).getText()).endsWith("epsilon.");
        assertThat(chunks.get(1).getText()).contains("zeta eta theta");
        assertThat(chunks).extracting(Document::getText)
                .allMatch(text -> !text.isBlank());
    }
}
