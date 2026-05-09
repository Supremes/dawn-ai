package com.dawn.ai.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TopicQueryService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}")
    private String vectorStoreTable;

    public List<String> listTopics() {
        String sql = String.format(
            "SELECT DISTINCT metadata->>'topicId' AS topic_id " +
            "FROM %s " +
            "WHERE metadata->>'topicId' IS NOT NULL " +
            "  AND metadata->>'topicId' != '' " +
            "ORDER BY topic_id",
            vectorStoreTable
        );
        return jdbcTemplate.queryForList(sql, String.class);
    }
}
