package com.dawn.ai.rag.retrieval.rerank;

import com.dawn.ai.rag.retrieval.RetrievalRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrossEncoderRetrievalReranker {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.rag.cross-encoder.base-url:}")
    private String baseUrl;

    @Value("${app.ai.rag.cross-encoder.rerank-path:/rerank}")
    private String rerankPath;

    @Value("${app.ai.rag.cross-encoder.model:}")
    private String model;

    @Value("${app.ai.rag.cross-encoder.api-key:}")
    private String apiKey;

    @Value("${app.ai.rag.cross-encoder.api-key-header:Authorization}")
    private String apiKeyHeader;

    @Value("${app.ai.rag.cross-encoder.api-key-prefix:Bearer }")
    private String apiKeyPrefix;

    @Value("${app.ai.rag.cross-encoder.max-document-chars:4000}")
    private int maxDocumentChars;

    public boolean isConfigured() {
        return StringUtils.hasText(baseUrl);
    }

    public List<Document> rerank(RetrievalRequest request, List<Document> candidates) {
        if (candidates.size() < 2 || !StringUtils.hasText(request.getQuery()) || !isConfigured()) {
            return candidates;
        }

        try {
            String responseBody = buildClient()
                    .post()
                    .uri(rerankPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildRequestPayload(request, candidates))
                    .retrieve()
                    .body(String.class);
            return mergeScores(candidates, parseScores(responseBody));
        } catch (RestClientException | IllegalArgumentException exception) {
            log.warn("[Reranker] Cross-encoder request failed, fallback to original ranking: {}", exception.getMessage());
            return candidates;
        } catch (Exception exception) {
            log.warn("[Reranker] Cross-encoder response parsing failed, fallback to original ranking", exception);
            return candidates;
        }
    }

    private RestClient buildClient() {
        RestClient.Builder builder = restClientBuilder.clone().baseUrl(baseUrl);
        if (StringUtils.hasText(apiKey)) {
            builder.defaultHeader(resolveHeaderName(), resolveHeaderValue());
        }
        return builder.build();
    }

    private Map<String, Object> buildRequestPayload(RetrievalRequest request, List<Document> candidates) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        if (StringUtils.hasText(model)) {
            payload.put("model", model);
        }
        payload.put("query", request.getQuery());
        payload.put("documents", candidates.stream().map(this::normalizeDocumentText).toList());
        payload.put("top_n", candidates.size());
        payload.put("return_documents", false);
        return payload;
    }

    private List<ScoredIndex> parseScores(String responseBody) throws Exception {
        if (!StringUtils.hasText(responseBody)) {
            return List.of();
        }

        JsonNode results = objectMapper.readTree(responseBody).path("results");
        if (!results.isArray()) {
            return List.of();
        }

        List<ScoredIndex> scoredIndices = new ArrayList<>();
        for (JsonNode result : results) {
            int index = result.path("index").asInt(-1);
            if (index < 0) {
                continue;
            }

            JsonNode scoreNode = result.has("relevance_score") ? result.get("relevance_score") : result.get("score");
            if (scoreNode == null || !scoreNode.isNumber()) {
                continue;
            }
            scoredIndices.add(new ScoredIndex(index, scoreNode.asDouble()));
        }
        return scoredIndices;
    }

    private List<Document> mergeScores(List<Document> candidates, List<ScoredIndex> scoredIndices) {
        if (scoredIndices.isEmpty()) {
            return candidates;
        }

        List<Document> reranked = new ArrayList<>();
        Set<Integer> seenIndices = new LinkedHashSet<>();
        scoredIndices.stream()
                .sorted(Comparator.comparingDouble(ScoredIndex::score).reversed()
                        .thenComparingInt(ScoredIndex::index))
                .forEach(scoredIndex -> {
                    if (scoredIndex.index() >= 0
                            && scoredIndex.index() < candidates.size()
                            && seenIndices.add(scoredIndex.index())) {
                        reranked.add(candidates.get(scoredIndex.index()));
                    }
                });

        IntStream.range(0, candidates.size())
                .filter(index -> !seenIndices.contains(index))
                .mapToObj(candidates::get)
                .forEach(reranked::add);
        return reranked;
    }

    private String normalizeDocumentText(Document document) {
        String text = document.getText();
        if (!StringUtils.hasText(text)) {
            return "";
        }
        if (text.length() <= maxDocumentChars) {
            return text;
        }
        return text.substring(0, maxDocumentChars);
    }

    private String resolveHeaderName() {
        return StringUtils.hasText(apiKeyHeader) ? apiKeyHeader : HttpHeaders.AUTHORIZATION;
    }

    private String resolveHeaderValue() {
        if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(resolveHeaderName())) {
            return (StringUtils.hasText(apiKeyPrefix) ? apiKeyPrefix : "Bearer ") + apiKey;
        }
        return (StringUtils.hasText(apiKeyPrefix) ? apiKeyPrefix : "") + apiKey;
    }

    private record ScoredIndex(int index, double score) {}
}