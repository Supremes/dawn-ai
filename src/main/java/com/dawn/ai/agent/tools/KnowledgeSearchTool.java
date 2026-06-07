package com.dawn.ai.agent.tools;

import com.dawn.ai.agent.trace.StepCollector;
import com.dawn.ai.rag.RagService;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Agent tool that searches the internal knowledge base.
 *
 * Placed in the tools package so ToolRegistry auto-discovers it.
 * ToolExecutionAspect intercepts apply() for step tracing and metrics automatically.
 *
 * 查询变换（rewrite + HyDE）由 {@link RagService#retrieve} 统一负责；本工具仅把
 * 用户传入的 query 和 metadata filter 透传过去。dedup key 使用原始 query + filters，
 * 保证同一次会话内重复的 Agent 调用仍会被跳过。
 *
 * Metadata filter 兜底（P0.3 推论）：{@code topicId} 与 {@code docId} 视为硬约束 ——
 * 当检索 0 命中时，仅丢弃软过滤（{@code source} / {@code category}）后重试，
 * 硬约束永远保留。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.rag.tool-enabled", havingValue = "true", matchIfMissing = true)
@Description("搜索内部知识库，获取与问题相关的背景信息。需要查询产品信息、技术文档或领域知识时调用。")
@RequiredArgsConstructor
public class KnowledgeSearchTool implements Function<KnowledgeSearchTool.Request, KnowledgeSearchTool.Response> {

    private static final Set<String> HARD_FILTER_KEYS = Set.of("topicId", "docId");

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
            String docId,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Research topic ID from the system prompt context. Always use the topicId value provided in the system prompt when one is present.")
            String topicId
    ) {
        public Request(String query) {
            this(query, null, null, null, null);
        }
    }
    public record Response(String context, int docsFound) {}

    @Override
    public Response apply(Request req) {
        Map<String, List<String>> appliedFilters = buildMetadataFilters(req);
        String retrievalKey = buildRetrievalKey(req.query(), appliedFilters);

        if (StepCollector.isQueryRetrieved(retrievalKey)) {
            dedupCounter.increment();
            log.info("[KnowledgeSearchTool] Skipping duplicate query: {}", retrievalKey);
            return new Response("（已检索过相同内容，请换个角度或直接生成回答）", 0);
        }
        StepCollector.markQueryRetrieved(retrievalKey);

        List<Document> docs = ragService.retrieve(RetrievalRequest.builder()
                .query(req.query())
                .topK(defaultTopK)
                .metadataFilters(appliedFilters)
                .build());

        // 兜底：当检索 0 命中且使用了软过滤（source/category）时，
        // 保留硬约束（topicId/docId）后重试。这样既能在 LLM 幻觉出
        // 不存在的 source/category 时不放弃，又能守住系统下发的研究
        // 主题边界，避免跨主题召回。
        if (docs.isEmpty() && hasSoftFilters(appliedFilters)) {
            Map<String, List<String>> hardOnly = retainHardFilters(appliedFilters);
            if (!hardOnly.equals(appliedFilters)) {
                log.warn("[KnowledgeSearchTool] 0 results with filters={}, retrying with hard filters only={}",
                        appliedFilters, hardOnly);
                docs = ragService.retrieve(RetrievalRequest.builder()
                        .query(req.query())
                        .topK(defaultTopK)
                        .metadataFilters(hardOnly)
                        .build());
            }
        }

        log.debug("[KnowledgeSearchTool] query='{}', filters={}, docsFound={}",
                req.query(), appliedFilters, docs.size());

        return new Response(formatContext(docs), docs.size());
    }

    private Map<String, List<String>> buildMetadataFilters(Request req) {
        Map<String, List<String>> filters = new LinkedHashMap<>();
        addFilter(filters, "source", req.source());
        addFilter(filters, "category", req.category());
        addFilter(filters, "docId", req.docId());
        addFilter(filters, "topicId", req.topicId());
        return filters;
    }

    private void addFilter(Map<String, List<String>> filters, String key, String value) {
        if (value != null && !value.isBlank()) {
            filters.put(key, List.of(value));
        }
    }

    private String buildRetrievalKey(String query, Map<String, List<String>> filters) {
        return query + "|" + filters;
    }

    private boolean hasSoftFilters(Map<String, List<String>> filters) {
        return filters.keySet().stream().anyMatch(key -> !HARD_FILTER_KEYS.contains(key));
    }

    private Map<String, List<String>> retainHardFilters(Map<String, List<String>> filters) {
        Map<String, List<String>> hard = new LinkedHashMap<>();
        filters.forEach((key, value) -> {
            if (HARD_FILTER_KEYS.contains(key)) {
                hard.put(key, value);
            }
        });
        return hard;
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
