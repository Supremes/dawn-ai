package com.dawn.ai.agent.tools;

import com.dawn.ai.service.QueryRewriter;
import com.dawn.ai.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeSearchToolTest {

    @Mock private QueryRewriter queryRewriter;
    @Mock private RagService ragService;

    private KnowledgeSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new KnowledgeSearchTool(queryRewriter, ragService);
        tool.setDefaultTopK(5);
    }

    @Test
    @DisplayName("apply 调用 queryRewriter.rewrite 并将改写后的查询传给 ragService")
    void apply_callsQueryRewriterAndRagService() {
        when(queryRewriter.rewrite("原始查询")).thenReturn("改写后查询");
        when(ragService.retrieve("改写后查询", 5)).thenReturn(List.of());

        tool.apply(new KnowledgeSearchTool.Request("原始查询"));

        verify(queryRewriter).rewrite("原始查询");
        verify(ragService).retrieve("改写后查询", 5);
    }

    @Test
    @DisplayName("找到文档时，context 格式为 [N] text，docsFound 等于文档数")
    void apply_docsFound_returnsFormattedContext() {
        Document doc1 = new Document("文档内容一");
        Document doc2 = new Document("文档内容二");
        when(queryRewriter.rewrite("查询")).thenReturn("查询");
        when(ragService.retrieve("查询", 5)).thenReturn(List.of(doc1, doc2));

        KnowledgeSearchTool.Response response = tool.apply(new KnowledgeSearchTool.Request("查询"));

        assertThat(response.docsFound()).isEqualTo(2);
        assertThat(response.context()).contains("[1] 文档内容一");
        assertThat(response.context()).contains("[2] 文档内容二");
    }

    @Test
    @DisplayName("无文档时返回未找到提示，docsFound 为 0")
    void apply_noDocsFound_returnsMissMessage() {
        when(queryRewriter.rewrite("查询")).thenReturn("查询");
        when(ragService.retrieve("查询", 5)).thenReturn(List.of());

        KnowledgeSearchTool.Response response = tool.apply(new KnowledgeSearchTool.Request("查询"));

        assertThat(response.docsFound()).isEqualTo(0);
        assertThat(response.context()).isEqualTo("未找到相关知识库内容。");
    }
}
