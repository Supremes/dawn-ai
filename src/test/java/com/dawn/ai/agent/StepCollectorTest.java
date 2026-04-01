package com.dawn.ai.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepCollectorTest {

    @BeforeEach
    void setUp() {
        StepCollector.init(10);
    }

    @AfterEach
    void tearDown() {
        StepCollector.clear();
    }

    @Test
    @DisplayName("isQueryRetrieved: init 后所有查询均未检索过")
    void isQueryRetrieved_afterInit_returnsFalse() {
        assertThat(StepCollector.isQueryRetrieved("any query")).isFalse();
    }

    @Test
    @DisplayName("markQueryRetrieved + isQueryRetrieved: 标记后应返回 true")
    void markQueryRetrieved_thenIsQueryRetrieved_returnsTrue() {
        StepCollector.markQueryRetrieved("Dawn AI 定价");

        assertThat(StepCollector.isQueryRetrieved("Dawn AI 定价")).isTrue();
    }

    @Test
    @DisplayName("isQueryRetrieved: 未标记的查询应返回 false")
    void isQueryRetrieved_unmarkedQuery_returnsFalse() {
        StepCollector.markQueryRetrieved("query A");

        assertThat(StepCollector.isQueryRetrieved("query B")).isFalse();
    }

    @Test
    @DisplayName("clear: 清理后 isQueryRetrieved 应返回 false")
    void clear_resetsRetrievedQueries() {
        StepCollector.markQueryRetrieved("some query");
        StepCollector.clear();
        StepCollector.init(10); // re-init for afterEach

        assertThat(StepCollector.isQueryRetrieved("some query")).isFalse();
    }

    @Test
    @DisplayName("init: 重新初始化后之前标记的查询应被清除")
    void init_clearsRetrievedQueries() {
        StepCollector.markQueryRetrieved("old query");

        StepCollector.init(10); // re-init

        assertThat(StepCollector.isQueryRetrieved("old query")).isFalse();
    }
}
