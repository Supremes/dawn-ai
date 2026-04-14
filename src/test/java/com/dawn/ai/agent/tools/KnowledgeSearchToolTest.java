package com.dawn.ai.agent.tools;

import com.dawn.ai.agent.trace.StepCollector;
import com.dawn.ai.rag.RagService;
import com.dawn.ai.rag.query.QueryRewriter;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchToolTest {

    @Mock private QueryRewriter queryRewriter;
    @Mock private RagService ragService;

    private KnowledgeSearchTool tool;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        tool = new KnowledgeSearchTool(queryRewriter, ragService, meterRegistry);
        tool.setDefaultTopK(5);
        tool.initMetrics();
        StepCollector.init(10);
    }

    @AfterEach
    void tearDown() {
        StepCollector.clear();
    }

    @Test
    @DisplayName("apply 调用 queryRewriter.rewrite 并将改写后的查询传给 ragService")
    void apply_callsQueryRewriterAndRagService() {
        when(queryRewriter.rewrite("原始查询")).thenReturn("改写后查询");
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of());

        tool.apply(new KnowledgeSearchTool.Request("原始查询"));

        verify(queryRewriter).rewrite("原始查询");
        org.mockito.ArgumentCaptor<RetrievalRequest> captor =
                org.mockito.ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(ragService).retrieve(captor.capture());
        assertThat(captor.getValue().getQuery()).isEqualTo("改写后查询");
        assertThat(captor.getValue().getTopK()).isEqualTo(5);
    }

    @Test
    @DisplayName("找到文档时，context 格式为 [N] text，docsFound 等于文档数")
    void apply_docsFound_returnsFormattedContext() {
        Document doc1 = new Document("文档内容一");
        Document doc2 = new Document("文档内容二");
        when(queryRewriter.rewrite("查询")).thenReturn("查询");
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of(doc1, doc2));

        KnowledgeSearchTool.Response response = tool.apply(new KnowledgeSearchTool.Request("查询"));

        assertThat(response.docsFound()).isEqualTo(2);
        assertThat(response.context()).contains("[1] 文档内容一");
        assertThat(response.context()).contains("[2] 文档内容二");
    }

    @Test
    @DisplayName("无文档时返回未找到提示，docsFound 为 0")
    void apply_noDocsFound_returnsMissMessage() {
        when(queryRewriter.rewrite("查询")).thenReturn("查询");
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of());

        KnowledgeSearchTool.Response response = tool.apply(new KnowledgeSearchTool.Request("查询"));

        assertThat(response.docsFound()).isEqualTo(0);
        assertThat(response.context()).isEqualTo("未找到相关知识库内容。");
    }

    @Test
    @DisplayName("apply: 相同改写查询第二次调用时跳过检索并返回提示")
    void apply_duplicateQuery_skipsRetrieval() {
        when(queryRewriter.rewrite("月费")).thenReturn("Dawn AI 定价 月费");
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of(new Document("¥99")));

        // 第一次调用 — 正常检索
        tool.apply(new KnowledgeSearchTool.Request("月费"));

        // 第二次相同查询 — 应跳过
        KnowledgeSearchTool.Response secondResponse =
                tool.apply(new KnowledgeSearchTool.Request("月费"));

        assertThat(secondResponse.docsFound()).isEqualTo(0);
        assertThat(secondResponse.context()).contains("已检索过");
        // ragService.retrieve 只被调用了一次（第二次被 dedup 拦截）
        verify(ragService, times(1)).retrieve(any(RetrievalRequest.class));
    }

    @Test
    @DisplayName("apply: 重复查询时 ai.rag.dedup.skipped 计数器 +1")
    void apply_duplicateQuery_incrementsDedupCounter() {
        when(queryRewriter.rewrite("test")).thenReturn("test rewritten");
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of());

        tool.apply(new KnowledgeSearchTool.Request("test"));
        tool.apply(new KnowledgeSearchTool.Request("test")); // duplicate

        double skipped = meterRegistry.counter("ai.rag.dedup.skipped").count();
        assertThat(skipped).isEqualTo(1.0);
    }

    @Test
    @DisplayName("apply: metadata 条件存在时应透传到 RetrievalRequest")
    void apply_passesMetadataFiltersToRetrievalRequest() {
        when(queryRewriter.rewrite("查询")).thenReturn("查询");
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of());

        tool.apply(new KnowledgeSearchTool.Request("查询", "pricing-doc", "billing", "doc-1"));

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
        when(queryRewriter.rewrite("月费")).thenReturn("Dawn AI 定价 月费");
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(List.of(new Document("¥99")));

        tool.apply(new KnowledgeSearchTool.Request("月费", null, "billing", null));
        tool.apply(new KnowledgeSearchTool.Request("月费", null, "pricing", null));

        verify(ragService, times(2)).retrieve(any(RetrievalRequest.class));
    }
}
