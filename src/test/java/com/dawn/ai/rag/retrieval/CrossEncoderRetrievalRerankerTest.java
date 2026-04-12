package com.dawn.ai.rag.retrieval;

import com.dawn.ai.rag.retrieval.rerank.CrossEncoderRetrievalReranker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CrossEncoderRetrievalRerankerTest {

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;
    private CrossEncoderRetrievalReranker reranker;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        reranker = new CrossEncoderRetrievalReranker(restClientBuilder, new ObjectMapper());
        ReflectionTestUtils.setField(reranker, "baseUrl", "https://rerank.test");
        ReflectionTestUtils.setField(reranker, "rerankPath", "/rerank");
        ReflectionTestUtils.setField(reranker, "model", "jina-reranker-v2-base-multilingual");
        ReflectionTestUtils.setField(reranker, "maxDocumentChars", 200);
    }

    @Test
    @DisplayName("rerank: cross-encoder 返回更高分数的文档应排在前面")
    void rerank_ordersByCrossEncoderScore() {
        server.expect(requestTo("https://rerank.test/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"results":[
                          {"index":1,"relevance_score":0.98},
                          {"index":0,"relevance_score":0.12}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<Document> reranked = reranker.rerank(
                RetrievalRequest.builder().query("refund policy").topK(2).build(),
                List.of(
                        new Document("weather forecast"),
                        new Document("refund policy details for Dawn AI users")
                ));

        assertThat(reranked).extracting(Document::getText)
                .containsExactly(
                        "refund policy details for Dawn AI users",
                        "weather forecast"
                );
        server.verify();
    }

    @Test
    @DisplayName("rerank: cross-encoder 返回空结果时应保留原始顺序")
    void rerank_keepsOriginalOrderWhenResponseHasNoScores() {
        server.expect(requestTo("https://rerank.test/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{" + "\"results\":[]}" , MediaType.APPLICATION_JSON));

        List<Document> candidates = List.of(
                new Document("first"),
                new Document("second")
        );

        List<Document> reranked = reranker.rerank(
                RetrievalRequest.builder().query("refund policy").topK(2).build(),
                candidates);

        assertThat(reranked).extracting(Document::getText).containsExactly("first", "second");
        server.verify();
    }
}