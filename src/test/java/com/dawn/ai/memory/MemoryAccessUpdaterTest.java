package com.dawn.ai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class MemoryAccessUpdaterTest {

    private NamedParameterJdbcTemplate jdbc;
    private MemoryAccessUpdater updater;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        updater = new MemoryAccessUpdater(jdbc);
    }

    @Test
    void updateAccessTime_updatesOnlyMemoryDocs() {
        Document memoryDoc = new Document("mem-1", "summary text",
                Map.of("type", "summary", "importance", 0.5));
        Document ragDoc = new Document("rag-1", "rag chunk",
                Map.of("source", "manual", "category", "general"));

        updater.updateAccessTime(List.of(memoryDoc, ragDoc));

        // Only mem-1 has 'type', so only one row in the batch
        verify(jdbc).batchUpdate(anyString(), argThat((MapSqlParameterSource[] params) ->
                params.length == 1 && "mem-1".equals(params[0].getValue("id"))
        ));
    }

    @Test
    void updateAccessTime_noOpsWhenAllRagDocs() {
        Document ragDoc = new Document("rag-1", "chunk",
                Map.of("source", "manual"));

        updater.updateAccessTime(List.of(ragDoc));

        verify(jdbc, never()).batchUpdate(anyString(), any(MapSqlParameterSource[].class));
    }

    @Test
    void updateAccessTime_noOpsForEmptyList() {
        updater.updateAccessTime(List.of());

        verify(jdbc, never()).batchUpdate(anyString(), any(MapSqlParameterSource[].class));
    }

    @Test
    void updateAccessTime_handlesJdbcFailureGracefully() {
        Document memoryDoc = new Document("mem-1", "summary",
                Map.of("type", "reflection", "importance", 0.9));
        when(jdbc.batchUpdate(anyString(), any(MapSqlParameterSource[].class)))
                .thenThrow(new RuntimeException("DB error"));

        // Should not propagate exception
        updater.updateAccessTime(List.of(memoryDoc));
    }

    @Test
    void updateAccessTime_updatesMultipleMemoryDocs() {
        Document summary   = new Document("s-1", "s", Map.of("type", "summary"));
        Document reflection = new Document("r-1", "r", Map.of("type", "reflection"));
        Document rag        = new Document("k-1", "k", Map.of("source", "file"));

        updater.updateAccessTime(List.of(summary, reflection, rag));

        verify(jdbc).batchUpdate(anyString(), argThat((MapSqlParameterSource[] params) ->
                params.length == 2
        ));
    }
}
