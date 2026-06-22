package com.dawn.ai.agent.tools;

import com.dawn.ai.agent.trace.StepCollector;
import com.dawn.ai.rag.RagService;
import com.dawn.ai.rag.retrieval.RetrievalRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchToolTest {

    @Mock private RagService ragService;

    private KnowledgeSearchTool tool;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        tool = new KnowledgeSearchTool(ragService, meterRegistry);
        tool.setDefaultTopK(5);
        tool.initMetrics();
        StepCollector.init(10);
    }

    @AfterEach
    void tearDown() {
        StepCollector.clear();
    }

    @Test
    @DisplayName("apply: 原始 query 直接透传给 RagService（rewrite/HyDE 由 RagService 内部处理）")
    void apply_forwardsOriginalQueryToRagService() {
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of());

        tool.apply(new KnowledgeSearchTool.Request("原始查询"));

        org.mockito.ArgumentCaptor<RetrievalRequest> captor =
                org.mockito.ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(ragService).retrieve(captor.capture());
        assertThat(captor.getValue().getQuery()).isEqualTo("原始查询");
        assertThat(captor.getValue().getTopK()).isEqualTo(5);
    }

    @Test
    @DisplayName("找到文档时，context 格式为 [N] text，docsFound 等于文档数")
    void apply_docsFound_returnsFormattedContext() {
        Document doc1 = new Document("文档内容一");
        Document doc2 = new Document("文档内容二");
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of(doc1, doc2));

        KnowledgeSearchTool.Response response = tool.apply(new KnowledgeSearchTool.Request("查询"));

        assertThat(response.docsFound()).isEqualTo(2);
        assertThat(response.context()).contains("[1] 文档内容一");
        assertThat(response.context()).contains("[2] 文档内容二");
    }

    @Test
    @DisplayName("无文档时返回未找到提示，docsFound 为 0")
    void apply_noDocsFound_returnsMissMessage() {
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of());

        KnowledgeSearchTool.Response response = tool.apply(new KnowledgeSearchTool.Request("查询"));

        assertThat(response.docsFound()).isEqualTo(0);
        assertThat(response.context()).isEqualTo("知识库中未找到相关内容。不要换关键词反复检索同类知识库问题；" +
                "若该问题涉及最新、当前、版本号、发布日期、官方资料或外部公开事实，请改用 webTool；" +
                "若可凭自身知识准确回答，请直接作答，不必派发子 Agent。");
    }

    @Test
    @DisplayName("apply: 相同 query+filters 第二次调用时跳过检索并返回提示")
    void apply_duplicateQuery_skipsRetrieval() {
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of(new Document("¥99")));

        tool.apply(new KnowledgeSearchTool.Request("月费"));
        KnowledgeSearchTool.Response secondResponse =
                tool.apply(new KnowledgeSearchTool.Request("月费"));

        assertThat(secondResponse.docsFound()).isEqualTo(0);
        assertThat(secondResponse.context()).contains("已检索过");
        verify(ragService, times(1)).retrieve(any(RetrievalRequest.class));
    }

    @Test
    @DisplayName("apply: 重复查询时 ai.rag.dedup.skipped 计数器 +1")
    void apply_duplicateQuery_incrementsDedupCounter() {
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of());

        tool.apply(new KnowledgeSearchTool.Request("test"));
        tool.apply(new KnowledgeSearchTool.Request("test"));

        double skipped = meterRegistry.counter("ai.rag.dedup.skipped").count();
        assertThat(skipped).isEqualTo(1.0);
    }

    @Test
    @DisplayName("apply: metadata 条件存在时应透传到 RetrievalRequest")
    void apply_passesMetadataFiltersToRetrievalRequest() {
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of(new Document("result")));

        tool.apply(new KnowledgeSearchTool.Request("查询", "pricing-doc", "billing", "doc-1", null));

        org.mockito.ArgumentCaptor<RetrievalRequest> captor =
                org.mockito.ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(ragService).retrieve(captor.capture());
        assertThat(captor.getValue().getMetadataFilters())
                .containsEntry("source", List.of("pricing-doc"))
                .containsEntry("category", List.of("billing"))
                .containsEntry("docId", List.of("doc-1"));
    }

    @Test
    @DisplayName("apply: 相同 query 但不同 metadata 条件时不应被 dedup 跳过")
    void apply_sameQueryDifferentMetadata_doesNotDedup() {
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of(new Document("¥99")));

        tool.apply(new KnowledgeSearchTool.Request("月费", null, "billing", null, null));
        tool.apply(new KnowledgeSearchTool.Request("月费", null, "pricing", null, null));

        verify(ragService, times(2)).retrieve(any(RetrievalRequest.class));
    }

    @Test
    @DisplayName("fallback: 软过滤(source/category)首次 0 命中时去掉软过滤重试，硬过滤(topicId)保留")
    void apply_softFilterFallback_preservesHardFilters() {
        when(ragService.retrieve(any(RetrievalRequest.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(new Document("hit")));

        KnowledgeSearchTool.Response response = tool.apply(
                new KnowledgeSearchTool.Request("登录失败", "wrong-source", "wrong-cat", null, "topic-42"));

        assertThat(response.docsFound()).isEqualTo(1);
        org.mockito.ArgumentCaptor<RetrievalRequest> captor =
                org.mockito.ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(ragService, times(2)).retrieve(captor.capture());

        // 第二次调用应只保留 topicId，移除 source/category
        Map<String, List<String>> retryFilters = captor.getAllValues().get(1).getMetadataFilters();
        assertThat(retryFilters)
                .containsEntry("topicId", List.of("topic-42"))
                .doesNotContainKey("source")
                .doesNotContainKey("category");
    }

    @Test
    @DisplayName("fallback: 只有硬过滤(topicId)时 0 命中不再重试")
    void apply_onlyHardFilters_noFallback() {
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of());

        tool.apply(new KnowledgeSearchTool.Request("登录失败", null, null, null, "topic-42"));

        verify(ragService, times(1)).retrieve(any(RetrievalRequest.class));
    }
}
