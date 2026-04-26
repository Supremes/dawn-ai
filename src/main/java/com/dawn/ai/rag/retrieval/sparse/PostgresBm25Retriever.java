package com.dawn.ai.rag.retrieval.sparse;

import com.dawn.ai.rag.retrieval.RetrievalRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

@Component
@RequiredArgsConstructor
public class PostgresBm25Retriever implements SparseRetriever {

    private static final Set<String> SUPPORTED_FILTER_KEYS = Set.of("source", "category", "docId");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.rag.sparse.text-search-config:english}")
    private String textSearchConfig = "english";

    @Override
    public List<Document> retrieve(RetrievalRequest request, int limit) {
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return List.of();
        }

        // Keep regconfig parameterized so the search config is application-configurable
        // without fragile string interpolation inside SQL text blocks.
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
        params.add(limit);

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
