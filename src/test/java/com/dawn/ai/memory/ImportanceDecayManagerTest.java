package com.dawn.ai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportanceDecayManagerTest {

    private NamedParameterJdbcTemplate jdbc;
    private ImportanceDecayManager manager;

    // halfLifeDays=30, minImportance=0.01, batchSize=500
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        manager = new ImportanceDecayManager(jdbc, 30.0, 0.01, 500);
    }

    // ── computeDecay unit tests ──────────────────────────────────────────────

    @Test
    void computeDecay_halvesImportanceAfterOneHalfLife() {
        long now = Instant.now().toEpochMilli();
        long thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli();

        double result = manager.computeDecay(0.5, thirtyDaysAgo, now);

        // After exactly one half-life, importance should be ~0.25 (halved)
        assertThat(result).isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void computeDecay_respectsMinImportanceFloor() {
        long now = Instant.now().toEpochMilli();
        long veryOld = Instant.now().minus(1000, ChronoUnit.DAYS).toEpochMilli();

        double result = manager.computeDecay(0.5, veryOld, now);

        assertThat(result).isEqualTo(0.01); // clamped to minImportance
    }

    @Test
    void computeDecay_noDecayForFutureOrNowTimestamp() {
        long now = Instant.now().toEpochMilli();

        assertThat(manager.computeDecay(0.5, now, now)).isEqualTo(0.5);
        assertThat(manager.computeDecay(0.5, now + 1000, now)).isEqualTo(0.5); // future
    }

    @Test
    void computeDecay_noDecayForRecentlyAccessedDoc() {
        long now = Instant.now().toEpochMilli();
        long oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS).toEpochMilli();

        double result = manager.computeDecay(0.5, oneHourAgo, now);

        // 1 hour / 30-day half-life → negligible decay, still very close to 0.5
        assertThat(result).isGreaterThan(0.499);
    }

    // ── decay() integration tests (JDBC mocked) ──────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void decay_appliesDecayToStaleDocuments() {
        long oldTs = Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli(); // one half-life
        var candidate = new ImportanceDecayManager.DecayCandidate("doc-1", 0.5, oldTs);

        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(candidate));

        manager.decay();

        // batchUpdate should be called with 1 update (delta ≈ 0.25 >> MIN_DELTA)
        verify(jdbc).batchUpdate(anyString(), argThat((MapSqlParameterSource[] params) ->
                params.length == 1
                && "doc-1".equals(params[0].getValue("id"))
                && (double) params[0].getValue("importance") < 0.5
        ));
    }

    @Test
    @SuppressWarnings("unchecked")
    void decay_skipsDocumentsWithInsignificantDelta() {
        long justNow = Instant.now().minus(1, ChronoUnit.HOURS).toEpochMilli();
        // 1 hour / 30-day half-life → delta << MIN_DELTA (0.001)
        var candidate = new ImportanceDecayManager.DecayCandidate("doc-fresh", 0.5, justNow);

        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(candidate));

        manager.decay();

        verify(jdbc, never()).batchUpdate(anyString(), any(MapSqlParameterSource[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void decay_noOpsWhenNoCandidates() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        manager.decay();

        verify(jdbc, never()).batchUpdate(anyString(), any(MapSqlParameterSource[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void decay_handlesJdbcQueryFailureGracefully() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenThrow(new RuntimeException("DB unreachable"));

        // Should not throw
        manager.decay();

        verify(jdbc, never()).batchUpdate(anyString(), any(MapSqlParameterSource[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void decay_processesMixedBatch() {
        long oldTs   = Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli(); // needs decay
        long freshTs = Instant.now().minus(1,  ChronoUnit.HOURS).toEpochMilli(); // skip

        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(
                        new ImportanceDecayManager.DecayCandidate("doc-old",   0.5, oldTs),
                        new ImportanceDecayManager.DecayCandidate("doc-fresh", 0.5, freshTs)
                ));

        manager.decay();

        // Only doc-old should be in the update batch
        verify(jdbc).batchUpdate(anyString(), argThat((MapSqlParameterSource[] params) ->
                params.length == 1 && "doc-old".equals(params[0].getValue("id"))
        ));
    }
}
