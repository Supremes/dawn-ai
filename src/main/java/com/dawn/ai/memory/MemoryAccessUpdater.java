package com.dawn.ai.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Asynchronously refreshes the {@code lastAccessedAt} metadata field for memory
 * documents that were hit during a retrieval call.
 *
 * <p>Only documents with a {@code type} metadata key are updated — these are
 * exclusively memory pipeline documents (summary, reflection). RAG knowledge
 * documents (ingested via {@code RagService.ingest}) have no {@code type} field
 * and are deliberately left untouched.
 *
 * <p>The update is fire-and-forget: failures are logged but never propagate to
 * the caller.
 */
@Slf4j
@Component
public class MemoryAccessUpdater {

    private static final String SQL_UPDATE_LAST_ACCESSED = """
            UPDATE vector_store
            SET metadata = jsonb_set(metadata, '{lastAccessedAt}', to_jsonb(:nowMs::bigint))
            WHERE id = :id::uuid
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MemoryAccessUpdater(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Filters the given documents to memory-only ones and updates their
     * {@code lastAccessedAt} asynchronously.
     *
     * @param docs documents returned by a retrieval call
     */
    @Async
    public void updateAccessTime(List<Document> docs) {
        long nowMs = Instant.now().toEpochMilli();

        MapSqlParameterSource[] params = docs.stream()
                .filter(doc -> doc.getMetadata().containsKey("type"))   // memory docs only
                .map(doc -> new MapSqlParameterSource()
                        .addValue("id", doc.getId())
                        .addValue("nowMs", nowMs))
                .toArray(MapSqlParameterSource[]::new);

        if (params.length == 0) {
            return;
        }

        try {
            jdbc.batchUpdate(SQL_UPDATE_LAST_ACCESSED, params);
            log.debug("[MemoryAccessUpdater] Updated lastAccessedAt for {} memory docs", params.length);
        } catch (Exception e) {
            log.warn("[MemoryAccessUpdater] Failed to update lastAccessedAt: {}", e.getMessage());
        }
    }
}
