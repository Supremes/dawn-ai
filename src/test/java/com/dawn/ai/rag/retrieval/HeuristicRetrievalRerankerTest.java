package com.dawn.ai.rag.retrieval;

import com.dawn.ai.rag.retrieval.rerank.HeuristicRetrievalReranker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicRetrievalRerankerTest {

    private final HeuristicRetrievalReranker reranker = new HeuristicRetrievalReranker();

    @Test
    @DisplayName("rerank: 查询词覆盖更高的文档应排在前面")
    void rerank_ordersByQueryCoverage() {
        List<Document> reranked = reranker.rerank(
                RetrievalRequest.builder().query("refund policy").topK(2).build(),
                List.of(
                        new Document("天气不错"),
                        new Document("refund policy details for Dawn AI users")
                ));

        assertThat(reranked).extracting(Document::getText)
                .containsExactly(
                        "refund policy details for Dawn AI users",
                        "天气不错"
                );
    }
}
