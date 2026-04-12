package com.dawn.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalRouterTest {

    private final RetrievalRouter router = new RetrievalRouter();

    @Test
    @DisplayName("route: 短关键词查询优先走 hybrid")
    void route_shortKeywordQuery_prefersHybrid() {
        RetrievalStrategy strategy = router.route(RetrievalRequest.builder()
                .query("refund policy")
                .build());

        assertThat(strategy).isEqualTo(RetrievalStrategy.HYBRID);
    }

    @Test
    @DisplayName("route: 带 metadata 过滤的查询优先走 dense")
    void route_metadataFilteredQuery_prefersDense() {
        RetrievalStrategy strategy = router.route(RetrievalRequest.builder()
                .query("refund policy")
                .metadataFilters(Map.of("category", List.of("billing")))
                .build());

        assertThat(strategy).isEqualTo(RetrievalStrategy.DENSE);
    }
}
