package com.dawn.ai.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * Periodically decays the {@code importance} metadata field of memory documents
 * based on how long ago they were last accessed ({@code lastAccessedAt}).
 *
 * <p>Formula (exponential decay with configurable half-life):
 * <pre>
 *   decayed = max(minImportance, current × 0.5 ^ (daysSinceAccess / halfLifeDays))
 * </pre>
 *
 * <p>After {@code halfLifeDays} days without any retrieval hit, importance halves.
 * Documents that decay below {@code app.memory.eviction.importance-threshold} will
 * subsequently be cleaned up by {@link EvictionPolicyManager}.
 *
 * <p>Uses {@link NamedParameterJdbcTemplate} directly to UPDATE the pgvector
 * {@code vector_store} table's metadata JSONB — avoids re-embedding on every decay cycle.
 */
@Slf4j
@Service
public class ImportanceDecayManager {

    private static final double LN2 = Math.log(2);
    // Only write back when the change is meaningful (avoids noisy DB churn)
    private static final double MIN_DELTA = 0.001;

    private static final String SQL_SELECT_CANDIDATES = """
            SELECT id,
                   (metadata->>'importance')::float                                              AS importance,
                   COALESCE((metadata->>'lastAccessedAt')::bigint,
                            (metadata->>'createdAt')::bigint)                                    AS last_accessed_at
            FROM vector_store
            WHERE metadata->>'type' IN ('summary')
              AND metadata ? 'importance'
              AND metadata ? 'createdAt'
            LIMIT :batchSize
            """;

    private static final String SQL_UPDATE_IMPORTANCE = """
            UPDATE vector_store
            SET metadata = jsonb_set(metadata, '{importance}', to_jsonb(:importance::float8))
            WHERE id = :id::uuid
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final double halfLifeDays;
    private final double minImportance;
    private final int batchSize;

    public ImportanceDecayManager(
            NamedParameterJdbcTemplate jdbc,
            @Value("${app.memory.decay.half-life-days:30}") double halfLifeDays,
            @Value("${app.memory.decay.min-importance:0.01}") double minImportance,
            @Value("${app.memory.decay.batch-size:500}") int batchSize) {
        this.jdbc = jdbc;
        this.halfLifeDays = halfLifeDays;
        this.minImportance = minImportance;
        this.batchSize = batchSize;
    }

    /** Runs daily at 03:30, 30 minutes after the eviction job (03:00). */
    @Scheduled(cron = "${app.memory.decay.cron:0 30 3 * * ?}")
    public void decay() {
        long nowMs = Instant.now().toEpochMilli();

        List<DecayCandidate> candidates;
        try {
            candidates = jdbc.query(
                    SQL_SELECT_CANDIDATES,
                    new MapSqlParameterSource("batchSize", batchSize),
                    (rs, rowNum) -> new DecayCandidate(
                            rs.getString("id"),
                            rs.getDouble("importance"),
                            rs.getLong("last_accessed_at")
                    ));
        } catch (Exception e) {
            log.warn("[ImportanceDecayManager] Failed to fetch decay candidates: {}", e.getMessage());
            return;
        }

        MapSqlParameterSource[] updates = candidates.stream()
                .flatMap(c -> {
                    double decayed = computeDecay(c.importance(), c.lastAccessedAt(), nowMs);
                    return Math.abs(decayed - c.importance()) >= MIN_DELTA
                            ? Stream.of(new MapSqlParameterSource()
                                    .addValue("id", c.id())
                                    .addValue("importance", decayed))
                            : Stream.empty();
                })
                .toArray(MapSqlParameterSource[]::new);

        if (updates.length == 0) {
            log.debug("[ImportanceDecayManager] No documents require importance decay");
            return;
        }

        jdbc.batchUpdate(SQL_UPDATE_IMPORTANCE, updates);
        log.info("[ImportanceDecayManager] Decayed importance for {} documents (halfLife={}d, min={})",
                updates.length, halfLifeDays, minImportance);
    }

    /**
     * Computes decayed importance. Package-private for unit testing.
     *
     * @param currentImportance current stored importance (0.0–1.0)
     * @param lastAccessedAtMs  epoch millis of last retrieval hit
     * @param nowMs             current epoch millis
     * @return decayed importance, floored at {@code minImportance}
     */
    double computeDecay(double currentImportance, long lastAccessedAtMs, long nowMs) {
        double daysSinceAccess = (nowMs - lastAccessedAtMs) / 86_400_000.0;
        if (daysSinceAccess <= 0) {
            return currentImportance;
        }
        double decayed = currentImportance * Math.exp(-LN2 * daysSinceAccess / halfLifeDays);
        return Math.max(minImportance, decayed);
    }

    // Package-private for test visibility
    record DecayCandidate(String id, double importance, long lastAccessedAt) {}
}
