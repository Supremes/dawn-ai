package com.dawn.ai.agent.tools;

import com.dawn.ai.agent.trace.StepCollector;
import com.dawn.ai.rag.RagService;
import com.dawn.ai.rag.query.QueryRewriter;
import com.dawn.ai.rag.retrieval.RetrievalRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Agent tool that searches the internal knowledge base.
 *
 * Placed in the tools package so ToolRegistry auto-discovers it.
 * ToolExecutionAspect intercepts apply() for step tracing and metrics automatically.
 *
 * Deduplication: uses StepCollector.isQueryRetrieved() to skip identical rewritten
 * queries within the same request, preventing wasted LLM + retrieval calls.
 */
@Slf4j
@Component
@Description("搜索内部知识库，获取与问题相关的背景信息。需要查询产品信息、技术文档或领域知识时调用。")
@RequiredArgsConstructor
public class KnowledgeSearchTool implements Function<KnowledgeSearchTool.Request, KnowledgeSearchTool.Response> {

    private final QueryRewriter queryRewriter;
    private final RagService ragService;
    private final MeterRegistry meterRegistry;

    @Setter
    @Value("${app.ai.rag.default-top-k:5}")
    private int defaultTopK;

    private Counter dedupCounter;

    /** package-private for test access */
    void initMetrics() {
        dedupCounter = Counter.builder("ai.rag.dedup.skipped")
                .description("RAG queries skipped due to deduplication within one request")
                .register(meterRegistry);
    }

    @PostConstruct
    void postConstruct() {
        initMetrics();
    }

    public record Request(
            @JsonProperty(required = true) String query,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Only set when the user explicitly names a source (e.g. 'search in devops-notes'). Do NOT guess or invent a value.")
            String source,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Only set when the user explicitly names a category. Do NOT guess or invent a value.")
            String category,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Only set when the user explicitly provides a document ID. Do NOT guess or invent a value.")
            String docId
    ) {
        public Request(String query) {
            this(query, null, null, null);
        }
    }
    public record Response(String context, int docsFound) {}

    @Override
    public Response apply(Request req) {
        String rewrittenQuery = queryRewriter.rewrite(req.query());
        String retrievalKey = buildRetrievalKey(rewrittenQuery, req);

        if (StepCollector.isQueryRetrieved(retrievalKey)) {
            dedupCounter.increment();
            log.info("[KnowledgeSearchTool] Skipping duplicate query: {}", retrievalKey);
            return new Response("（已检索过相同内容，请换个角度或直接生成回答）", 0);
        }
        StepCollector.markQueryRetrieved(retrievalKey);

        Map<String, List<String>> appliedFilters = buildMetadataFilters(req);
        List<Document> docs = ragService.retrieve(RetrievalRequest.builder()
                .query(rewrittenQuery)
                .topK(defaultTopK)
                .metadataFilters(appliedFilters)
                .build());

        // Fallback: if metadata filters caused zero results, retry without filters.
        // This guards against the LLM hallucinating metadata values that don't exist in the store.
        if (docs.isEmpty() && !appliedFilters.isEmpty()) {
            log.warn("[KnowledgeSearchTool] 0 results with filters={}, retrying without metadata filters", appliedFilters);
            docs = ragService.retrieve(RetrievalRequest.builder()
                    .query(rewrittenQuery)
                    .topK(defaultTopK)
                    .build());
        }

        log.debug("[KnowledgeSearchTool] query='{}' → rewritten='{}', docsFound={}",
                req.query(), rewrittenQuery, docs.size());

        return new Response(formatContext(docs), docs.size());
    }

    private Map<String, List<String>> buildMetadataFilters(Request req) {
        Map<String, List<String>> filters = new LinkedHashMap<>();
        addFilter(filters, "source", req.source());
        addFilter(filters, "category", req.category());
        addFilter(filters, "docId", req.docId());
        return filters;
    }

    private void addFilter(Map<String, List<String>> filters, String key, String value) {
        if (value != null && !value.isBlank()) {
            filters.put(key, List.of(value));
        }
    }

    private String buildRetrievalKey(String rewrittenQuery, Request req) {
        return rewrittenQuery + "|" + buildMetadataFilters(req);
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
