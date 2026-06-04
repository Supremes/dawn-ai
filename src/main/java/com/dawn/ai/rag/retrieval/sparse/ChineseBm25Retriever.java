package com.dawn.ai.rag.retrieval.sparse;

import com.dawn.ai.rag.retrieval.RetrievalRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.IntStream;

/**
 * Chinese BM25 retriever with jieba tokenization and proper BM25 scoring.
 *
 * Pipeline:
 *   1. Use PostgreSQL FTS (zhparser) to fetch candidate documents efficiently
 *   2. Tokenize query and documents with jieba
 *   3. Score candidates using Okapi BM25 (TF-IDF + document length normalization)
 *   4. Return documents sorted by BM25 score
 *
 * Activated by: app.ai.rag.sparse.retriever-type=chinese-bm25
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ai.rag.sparse.retriever-type", havingValue = "chinese-bm25")
public class ChineseBm25Retriever implements SparseRetriever {

    private static final Set<String> SUPPORTED_FILTER_KEYS = Set.of("source", "category", "docId", "topicId");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ChineseTokenizer tokenizer;
    private final Bm25Scorer bm25Scorer;

    @Value("${app.ai.rag.sparse.text-search-config:chinese}")
    private String textSearchConfig = "chinese";

    @Override
    public List<Document> retrieve(RetrievalRequest request, int limit) {
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return List.of();
        }

        // Phase 1: Fetch candidates from PostgreSQL FTS (over-fetch for BM25 rescoring)
        int candidateLimit = limit * 3;
        List<Document> candidates = fetchCandidates(request, candidateLimit);
        if (candidates.isEmpty()) {
            return List.of();
        }

        // Phase 2: Tokenize query and documents with jieba
        List<String> queryTokens = tokenizer.tokenize(request.getQuery());
        if (queryTokens.isEmpty()) {
            return candidates.stream().limit(limit).toList();
        }

        List<List<String>> docTokens = candidates.stream()
                .map(doc -> tokenizer.tokenize(doc.getText()))
                .toList();

        // Phase 3: Score with BM25
        double[] scores = bm25Scorer.score(queryTokens, docTokens);

        // Phase 4: Sort by BM25 score descending, return top limit
        return IntStream.range(0, candidates.size())
                .boxed()
                .sorted((a, b) -> Double.compare(scores[b], scores[a]))
                .limit(limit)
                .map(idx -> {
                    Document doc = candidates.get(idx);
                    doc.getMetadata().put("bm25Score", scores[idx]);
                    return doc;
                })
                .toList();
    }

    private List<Document> fetchCandidates(RetrievalRequest request, int candidateLimit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, content, metadata
                FROM vector_store
                WHERE to_tsvector(CAST(? AS regconfig), content)
                  @@ websearch_to_tsquery(CAST(? AS regconfig), ?)
                """);
        List<Object> params = new ArrayList<>();
        params.add(textSearchConfig);
        params.add(textSearchConfig);
        params.add(request.getQuery());
        appendMetadataFilters(sql, params, request.getMetadataFilters());
        sql.append("""
                ORDER BY ts_rank_cd(
                to_tsvector(CAST(? AS regconfig), content),
                websearch_to_tsquery(CAST(? AS regconfig), ?)
                ) DESC
                LIMIT ?
                """);
        params.add(textSearchConfig);
        params.add(textSearchConfig);
        params.add(request.getQuery());
        params.add(candidateLimit);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new Document(
                rs.getString("id"),
                rs.getString("content"),
                readMetadata(rs.getString("metadata"))), params.toArray());
    }

    private void appendMetadataFilters(StringBuilder sql, List<Object> params, Map<String, List<String>> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return;
        }

        for (Map.Entry<String, List<String>> entry : metadataFilters.entrySet()) {
            if (!SUPPORTED_FILTER_KEYS.contains(entry.getKey()) || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            StringJoiner placeholders = new StringJoiner(", ");
            entry.getValue().forEach(value -> {
                placeholders.add("?");
                params.add(value);
            });
            sql.append("\nAND metadata ->> '")
                    .append(entry.getKey())
                    .append("' IN (")
                    .append(placeholders)
                    .append(")");
        }
    }

    private Map<String, Object> readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse metadata JSON", exception);
        }
    }
}
