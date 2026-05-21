package com.dawn.ai.rag.retrieval;

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
    @DisplayName("route: 带 metadata 过滤的短关键词查询仍走 hybrid（metadata 只缩小范围，不改变策略）")
    void route_metadataFilteredShortQuery_stillPrefersHybrid() {
        RetrievalStrategy strategy = router.route(RetrievalRequest.builder()
                .query("refund policy")
                .metadataFilters(Map.of("category", List.of("billing")))
                .build());

        assertThat(strategy).isEqualTo(RetrievalStrategy.HYBRID);
    }

    @Test
    @DisplayName("route: 带 metadata 过滤的长自然语言问题走 dense")
    void route_metadataFilteredLongQuery_prefersDense() {
        RetrievalStrategy strategy = router.route(RetrievalRequest.builder()
                .query("请详细解释 Dawn AI 的退款政策以及如何申请退款")
                .metadataFilters(Map.of("category", List.of("billing")))
                .build());

        assertThat(strategy).isEqualTo(RetrievalStrategy.DENSE);
    }

    @Test
    @DisplayName("isShortQuery: ≤3 tokens 视为短查询；isShortQuery 为 true 时不应启用 HyDE")
    void helpers_classifyQueryShape() {
        assertThat(router.isShortQuery("登录失败")).isTrue();
        assertThat(router.isShortQuery("refund policy")).isTrue();
        assertThat(router.isShortQuery("请详细解释 Dawn AI 的退款流程")).isFalse();
        assertThat(router.looksLikeExactLookup("\"exact match\"")).isTrue();
        assertThat(router.looksLikeExactLookup("error code 500")).isTrue();
        assertThat(router.looksLikeExactLookup("登录失败")).isFalse();
    }
}
