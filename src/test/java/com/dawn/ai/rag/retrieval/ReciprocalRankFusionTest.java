package com.dawn.ai.rag.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {

    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion();

    @Test
    @DisplayName("fuse: 同时命中 dense 和 sparse 的文档应优先排前")
    void fuse_prioritizesDocumentsPresentInBothRankings() {
        Document denseOnly = new Document("doc-1", "dense only", Map.of());
        Document both = new Document("doc-2", "both", Map.of());
        Document sparseOnly = new Document("doc-3", "sparse only", Map.of());

        List<Document> fused = fusion.fuse(
                List.of(denseOnly, both),
                List.of(both, sparseOnly));

        assertThat(fused).extracting(Document::getId)
                .startsWith("doc-2");
    }
}
