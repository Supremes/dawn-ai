package com.dawn.ai.service;

import com.dawn.ai.config.AiAvailabilityChecker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.UUID;

/**
 * RAG (Retrieval Augmented Generation) Service.
 *
 * Pipeline: Document → Chunk(500 tokens, overlap=50) → Embed → Store
 *           → Query → SimilarityThreshold filter → Augment Prompt → Generate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final VectorStore vectorStore;
    private final MeterRegistry meterRegistry;
    private final AiAvailabilityChecker aiAvailabilityChecker;
    private final RetrievalReranker retrievalReranker;

    @Setter
    @Value("${app.ai.rag.similarity-threshold:0.7}")
    private double similarityThreshold;

    @Setter
    @Value("${app.ai.rag.default-top-k:5}")
    private int defaultTopK;

    @Setter
    @Value("${app.ai.rag.chunk-size:500}")
    private int chunkSize = 500;

    @Setter
    @Value("${app.ai.rag.chunk-overlap:50}")
    private int chunkOverlap = 50;

    @Setter
    @Value("${app.ai.rag.rerank-enabled:true}")
    private boolean rerankEnabled = true;

    private Counter ingestionCounter;
    private Counter retrievalHitCounter;
    private Counter retrievalMissCounter;
    private DistributionSummary filteredCountSummary;
    private DocumentTransformer splitter;

    @PostConstruct
    void initMetrics() {
        ingestionCounter = Counter.builder("ai.rag.ingestion.total")
                .description("Total documents ingested into vector store")
                .register(meterRegistry);
        retrievalHitCounter = Counter.builder("ai.rag.retrieval.total")
                .description("Total RAG retrieval queries")
                .tag("result", "hit")
                .register(meterRegistry);
        retrievalMissCounter = Counter.builder("ai.rag.retrieval.total")
                .description("Total RAG retrieval queries")
                .tag("result", "miss")
                .register(meterRegistry);
        filteredCountSummary = DistributionSummary.builder("ai.rag.retrieval.filtered_count")
                .description("Documents filtered out per retrieval (candidates - returned)")
                .register(meterRegistry);
        initSplitter();
    }

    void initSplitter() {
        splitter = new OverlapTextSplitter(chunkSize, chunkOverlap);
    }

    /**
     * Ingest a document into the vector store.
     * Long documents are split into chunks of ~500 tokens with 50-token overlap.
     * Each chunk inherits the parent document's source and category metadata.
     */
    public String ingest(String content, String source, String category) {
        aiAvailabilityChecker.ensureConfigured();

        Map<String, Object> metadata = Map.of(
                "source", source != null ? source : "manual",
                "category", category != null ? category : "general"
        );
        Document parentDoc = new Document(UUID.randomUUID().toString(), content, metadata);

        List<Document> chunks = splitter.apply(List.of(parentDoc));

        vectorStore.add(chunks);
        ingestionCounter.increment(chunks.size());

        log.info("[RagService] Ingested {} chunk(s), source={}", chunks.size(), source);
        return parentDoc.getId();
    }

    /**
     * Retrieve top-K semantically similar documents for a query.
     *
     * Strategy:
     *  1. Request topK*2 candidates from vector store with similarityThreshold filter.
     *  2. Record how many candidates were filtered out (candidates - returned).
     *  3. Limit final result to topK.
     */
    public List<Document> retrieve(String query, int topK) {
        return retrieve(RetrievalRequest.builder()
                .query(query)
                .topK(topK)
                .build());
    }

    public List<Document> retrieve(RetrievalRequest retrievalRequest) {
        aiAvailabilityChecker.ensureConfigured();

        int candidateCount = retrievalRequest.getTopK() * 2;
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(retrievalRequest.getQuery())
                .topK(candidateCount)
                .similarityThreshold(similarityThreshold);

        Filter.Expression filterExpression = buildFilterExpression(retrievalRequest.getMetadataFilters());
        if (filterExpression != null) {
            builder.filterExpression(filterExpression);
        }

        SearchRequest request = builder.build();

        List<Document> results = vectorStore.similaritySearch(request);
        int filteredOut = Math.max(0, candidateCount - results.size());
        filteredCountSummary.record(filteredOut);

        if (results.isEmpty()) {
            retrievalMissCounter.increment();
        } else {
            retrievalHitCounter.increment();
        }

        List<Document> reranked = shouldRerank(retrievalRequest)
                ? retrievalReranker.rerank(retrievalRequest, results)
                : results;
        List<Document> limited = reranked.stream().limit(retrievalRequest.getTopK()).toList();
        log.info("[RagService] Retrieved {}/{} docs (threshold={}, filtered={}), query='{}', metadataFilters={}",
                limited.size(), candidateCount, similarityThreshold, filteredOut,
                retrievalRequest.getQuery(), retrievalRequest.getMetadataFilters());
        return limited;
    }

    private boolean shouldRerank(RetrievalRequest retrievalRequest) {
        return rerankEnabled && retrievalRequest.isRerankEnabled();
    }

    private Filter.Expression buildFilterExpression(Map<String, List<String>> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return null;
        }

        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op combined = null;
        for (Map.Entry<String, List<String>> entry : metadataFilters.entrySet()) {
            List<Object> values = entry.getValue() == null
                    ? List.of()
                    : new ArrayList<>(entry.getValue().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> (Object) value)
                    .toList());
            if (values.isEmpty()) {
                continue;
            }

            FilterExpressionBuilder.Op current = values.size() == 1
                    ? builder.eq(entry.getKey(), values.get(0))
                    : builder.in(entry.getKey(), values);
            combined = combined == null ? current : builder.and(combined, current);
        }
        return combined == null ? null : combined.build();
    }
}
