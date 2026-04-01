package com.dawn.ai.agent.tools;

import com.dawn.ai.service.QueryRewriter;
import com.dawn.ai.service.RagService;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.context.annotation.Description;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

@Slf4j
@Component
@Description("搜索内部知识库，获取与问题相关的背景信息。需要查询产品信息、技术文档或领域知识时调用。")
@RequiredArgsConstructor
public class KnowledgeSearchTool implements Function<KnowledgeSearchTool.Request, KnowledgeSearchTool.Response> {

    public record Request(@JsonProperty(required = true) String query) {}
    public record Response(String context, int docsFound) {}

    private final QueryRewriter queryRewriter;
    private final RagService ragService;

    @Setter
    @Value("${app.ai.rag.default-top-k:5}")
    private int defaultTopK;

    @Override
    public Response apply(Request req) {
        String rewrittenQuery = queryRewriter.rewrite(req.query());
        List<Document> docs = ragService.retrieve(rewrittenQuery, defaultTopK);
        String context = formatContext(docs);
        log.debug("[KnowledgeSearchTool] query='{}' → rewritten='{}', docsFound={}",
                req.query(), rewrittenQuery, docs.size());
        return new Response(context, docs.size());
    }

    private String formatContext(List<Document> docs) {
        if (docs.isEmpty()) return "未找到相关知识库内容。";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            sb.append(String.format("[%d] %s\n", i + 1, docs.get(i).getText()));
        }
        return sb.toString();
    }
}
