package com.dawn.ai.service;

import com.dawn.ai.config.AiAvailabilityChecker;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TextChunker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * RAG (Retrieval Augmented Generation) Service.
 *
 * Pipeline: Document → Chunk(500 tokens, overlap=50) → Embed → Store
 *           → QueryRewrite → SimilarityThreshold filter → Augment Prompt → Generate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final MeterRegistry meterRegistry;
    private final AiAvailabilityChecker aiAvailabilityChecker;
    private final Knowledge knowledge;
    private final QueryRewriter queryRewriter;

    @Setter
    @Value("${app.ai.rag.similarity-threshold:0.7}")
    private double similarityThreshold;

    @Setter
    @Value("${app.ai.rag.default-top-k:5}")
    private int defaultTopK;

    @Setter
    @Value("${app.ai.rag.fallback-similarity-threshold:0.5}")
    private double fallbackSimilarityThreshold;

    @Setter
    @Value("${app.ai.rag.short-query-max-length:12}")
    private int shortQueryMaxLength;

    private Counter ingestionCounter;
    private Counter retrievalHitCounter;
    private Counter retrievalMissCounter;
    private DistributionSummary filteredCountSummary;

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
    }

    /**
     * Ingest a document into the vector store.
     * Long documents are split into chunks of ~500 tokens with 50-token overlap.
     * Each chunk inherits the parent document's source and category metadata.
     */
    public void ingest(String content, String source, String category) {
        aiAvailabilityChecker.ensureConfigured();

        String docId = UUID.randomUUID().toString();
        Map<String, Object> payload = new HashMap<>();
        payload.put("source", source != null ? source : "manual");
        payload.put("category", category != null ? category : "general");

        List<String> chunks = TextChunker.chunkText(content, 500, SplitStrategy.PARAGRAPH, 50);

        List<io.agentscope.core.rag.model.Document> documents = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            DocumentMetadata metadata = DocumentMetadata.builder()
                    .content(TextBlock.builder().text(chunks.get(index)).build())
                    .docId(docId)
                    .chunkId(String.valueOf(index))
                    .payload(payload)
                    .build();

            documents.add(new io.agentscope.core.rag.model.Document(metadata));
        }

        knowledge.addDocuments(documents).block();
        ingestionCounter.increment(documents.size());
        log.info("[RagService] Ingested {} chunk(s), source={}", documents.size(), source);
    }

    /**
     * Retrieve top-K semantically similar documents for a query.
     * Applies query rewriting before retrieval when enabled.
     */
    public List<io.agentscope.core.rag.model.Document> retrieve(String query, int topK) {
        aiAvailabilityChecker.ensureConfigured();

        String rewrittenQuery = queryRewriter.rewrite(query);
        List<io.agentscope.core.rag.model.Document> results = retrieveWithThreshold(rewrittenQuery, topK, similarityThreshold);
        boolean usedFallbackThreshold = false;

        if ((results == null || results.isEmpty()) && shouldRetryWithFallback(rewrittenQuery)) {
            usedFallbackThreshold = true;
            log.info("[RagService] Retrieved 0 docs at threshold={} for short query='{}', retrying with fallback threshold={}",
                    similarityThreshold, rewrittenQuery, fallbackSimilarityThreshold);
            results = retrieveWithThreshold(rewrittenQuery, topK, fallbackSimilarityThreshold);
        }

        if (results == null || results.isEmpty()) {
            retrievalMissCounter.increment();
            log.info("[RagService] Retrieved 0/{} docs (threshold={}), query='{}', rewritten='{}'",
                    topK, similarityThreshold, query, rewrittenQuery);
            return List.of();
        }

        retrievalHitCounter.increment();
        if (usedFallbackThreshold) {
            log.info("[RagService] Retrieved {}/{} docs after fallback (primaryThreshold={}, fallbackThreshold={}), query='{}', rewritten='{}'",
                    results.size(), topK, similarityThreshold, fallbackSimilarityThreshold, query, rewrittenQuery);
        } else {
            log.info("[RagService] Retrieved {}/{} docs (threshold={}), query='{}', rewritten='{}'",
                    results.size(), topK, similarityThreshold, query, rewrittenQuery);
        }
        return results;
    }

    private List<io.agentscope.core.rag.model.Document> retrieveWithThreshold(String query, int topK, double threshold) {
        RetrieveConfig config = RetrieveConfig.builder()
                .scoreThreshold(threshold)
                .limit(topK)
                .build();
        return knowledge.retrieve(query, config).block();
    }

    private boolean shouldRetryWithFallback(String query) {
        if (fallbackSimilarityThreshold >= similarityThreshold) {
            return false;
        }

        String normalizedQuery = query == null ? "" : query.replaceAll("\\s+", "");
        if (normalizedQuery.isEmpty()) {
            return false;
        }

        return normalizedQuery.codePointCount(0, normalizedQuery.length()) <= shortQueryMaxLength;
    }
}
