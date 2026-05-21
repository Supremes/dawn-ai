package com.dawn.ai.rag;

import com.dawn.ai.config.AiAvailabilityChecker;
import com.dawn.ai.memory.MemoryAccessUpdater;
import com.dawn.ai.rag.ingestion.OverlapTextSplitter;
import com.dawn.ai.rag.query.HydeQueryGenerator;
import com.dawn.ai.rag.query.QueryRewriter;
import com.dawn.ai.rag.retrieval.fusion.ReciprocalRankFusion;
import com.dawn.ai.rag.retrieval.RetrievalRequest;
import com.dawn.ai.rag.retrieval.rerank.CrossEncoderRetrievalReranker;
import com.dawn.ai.rag.retrieval.rerank.RetrievalReranker;
import com.dawn.ai.rag.retrieval.RetrievalRouter;
import com.dawn.ai.rag.retrieval.RetrievalStrategy;
import com.dawn.ai.rag.retrieval.sparse.SparseRetriever;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * RAG (Retrieval Augmented Generation) Service.
 *
 * Pipeline: Document → Chunk(500 tokens, overlap=50) → Embed → Store
 *           → Query → SimilarityThreshold filter → Augment Prompt → Generate
 */
@Slf4j
@Service
public class RagService {

    private final VectorStore vectorStore;
    private final MeterRegistry meterRegistry;
    private final AiAvailabilityChecker aiAvailabilityChecker;
    private final RetrievalReranker retrievalReranker;
    private final SparseRetriever sparseRetriever;
    private final ReciprocalRankFusion reciprocalRankFusion;
    private final RetrievalRouter retrievalRouter;
    private final DocumentTransformer splitter;
    // Keep an explicit constructor with parameter-level @Qualifier because the
    // application defines multiple ExecutorService beans and this dependency must
    // bind to the retrieval pool rather than relying on type-only resolution.
    private final ExecutorService ragRetrievalExecutor;
    private final MemoryAccessUpdater memoryAccessUpdater;
    private final HydeQueryGenerator hydeQueryGenerator;
    private final QueryRewriter queryRewriter;

    public RagService(VectorStore vectorStore,
                      MeterRegistry meterRegistry,
                      AiAvailabilityChecker aiAvailabilityChecker,
                      RetrievalReranker retrievalReranker,
                      SparseRetriever sparseRetriever,
                      ReciprocalRankFusion reciprocalRankFusion,
                      RetrievalRouter retrievalRouter,
                      DocumentTransformer splitter,
                      @Qualifier("ragRetrievalExecutor") ExecutorService ragRetrievalExecutor,
                      MemoryAccessUpdater memoryAccessUpdater,
                      HydeQueryGenerator hydeQueryGenerator,
                      QueryRewriter queryRewriter) {
        this.vectorStore = vectorStore;
        this.meterRegistry = meterRegistry;
        this.aiAvailabilityChecker = aiAvailabilityChecker;
        this.retrievalReranker = retrievalReranker;
        this.sparseRetriever = sparseRetriever;
        this.reciprocalRankFusion = reciprocalRankFusion;
        this.retrievalRouter = retrievalRouter;
        this.splitter = splitter;
        this.ragRetrievalExecutor = ragRetrievalExecutor;
        this.memoryAccessUpdater = memoryAccessUpdater;
        this.hydeQueryGenerator = hydeQueryGenerator;
        this.queryRewriter = queryRewriter;
    }

    @Setter
    @Value("${app.ai.rag.similarity-threshold:0.7}")
    private double similarityThreshold;

    @Setter
    @Value("${app.ai.rag.rerank-enabled:true}")
    private boolean rerankEnabled = true;

    @Setter
    @Value("${app.ai.rag.hybrid-enabled:true}")
    private boolean hybridEnabled = true;

    /**
     * Minimum cross-encoder rerank score; documents whose {@code rerankScore} metadata
     * (written by {@link CrossEncoderRetrievalReranker}) falls below this threshold are
     * dropped before being returned. 0.0 disables filtering. Documents without a rerank
     * score (e.g. heuristic reranker, or rerank disabled) are never filtered out by this.
     */
    @Setter
    @Value("${app.ai.rag.reranker.min-score:0.0}")
    private double rerankMinScore = 0.0;

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
     * Ingest a document. Delegates to {@link #ingest(String, String, String, String)} with no topicId.
     */
    public String ingest(String content, String source, String category) {
        return ingest(content, source, category, null);
    }

    public String ingest(String content, String source, String category, String topicId) {
        aiAvailabilityChecker.ensureConfigured();

        String docId = UUID.randomUUID().toString();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", source != null ? source : "manual");
        metadata.put("category", category != null ? category : "general");
        metadata.put("docId", docId);
        if (topicId != null && !topicId.isBlank()) {
            metadata.put("topicId", topicId);
        }
        Document parentDoc = new Document(docId, content, metadata);

        List<Document> chunks = splitter.apply(List.of(parentDoc));

        vectorStore.add(chunks);
        ingestionCounter.increment(chunks.size());

        log.info("[RagService] Ingested {} chunk(s), source={}, topicId={}", chunks.size(), source, topicId);
        return docId;
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

        // ── Query transformation pipeline ──────────────────────────────
        // 1. Optional LLM rewrite (keyword normalization, removes filler words).
        // 2. Strategy routing on the rewritten query (short/keyword → HYBRID; otherwise DENSE).
        // 3. Optional HyDE expansion — ONLY for the dense branch and only when the query is
        //    long-form natural language. Short queries / exact lookups / metadata-scoped
        //    queries skip HyDE to avoid embedding-space drift.
        String originalQuery = retrievalRequest.getQuery();
        String rewrittenQuery = queryRewriter.rewrite(originalQuery);
        RetrievalRequest effectiveRequest = rewrittenQuery.equals(originalQuery)
                ? retrievalRequest
                : retrievalRequest.toBuilder().query(rewrittenQuery).build();

        RetrievalStrategy strategy = resolveStrategy(effectiveRequest);
        String denseQuery = shouldUseHyde(strategy, effectiveRequest)
                ? hydeQueryGenerator.generate(rewrittenQuery)
                : rewrittenQuery;

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(denseQuery)
                .topK(candidateCount)
                .similarityThreshold(similarityThreshold);

        Filter.Expression filterExpression = buildFilterExpression(effectiveRequest.getMetadataFilters());
        if (filterExpression != null) {
            builder.filterExpression(filterExpression);
        }

        SearchRequest request = builder.build();

        CompletableFuture<List<Document>> denseFuture = CompletableFuture.supplyAsync(
            () -> vectorStore.similaritySearch(request),
            ragRetrievalExecutor);
        // Sparse retriever uses the rewritten (keyword-friendly) query, NOT the HyDE
        // expansion — BM25 wants concise tokens, not a paragraph of hypothetical answer.
        CompletableFuture<List<Document>> sparseFuture = CompletableFuture.supplyAsync(() -> shouldUseHybridSearch(strategy)
                ? sparseRetriever.retrieve(effectiveRequest, candidateCount)
                : List.of(),
            ragRetrievalExecutor);

        CompletableFuture.allOf(denseFuture, sparseFuture).join();

        List<Document> denseResults = denseFuture.join();
        List<Document> sparseResults = sparseFuture.join();
        log.debug("[RagService] Retrieval candidates: dense={}, sparse={}, strategy={}, denseQuery='{}', sparseQuery='{}'",
            denseResults.size(), sparseResults.size(), strategy, denseQuery, rewrittenQuery);
        List<Document> results = shouldUseHybridSearch(strategy)
            ? reciprocalRankFusion.fuse(denseResults, sparseResults)
            : denseResults;

        int filteredOut = Math.max(0, candidateCount - results.size());
        filteredCountSummary.record(filteredOut);

        if (results.isEmpty()) {
            retrievalMissCounter.increment();
        } else {
            retrievalHitCounter.increment();
        }

        List<Document> reranked = shouldRerank(effectiveRequest)
                ? retrievalReranker.rerank(effectiveRequest, results)
                : results;
        List<Document> filtered = applyRerankMinScore(reranked);
        List<Document> limited = filtered.stream().limit(effectiveRequest.getTopK()).toList();
        log.info("[RagService] Retrieved {}/{} docs (strategy={}, threshold={}, filtered={}, rerankDropped={}), originalQuery='{}', rewritten='{}', denseQuery='{}', metadataFilters={}",
                limited.size(), candidateCount, strategy, similarityThreshold, filteredOut,
                reranked.size() - filtered.size(),
                originalQuery, rewrittenQuery, denseQuery, effectiveRequest.getMetadataFilters());
        if (log.isDebugEnabled()) {
            limited.forEach(doc -> log.debug("[RagService]   → docId={} source={} category={} rerankScore={}",
                    doc.getMetadata().get("docId"),
                    doc.getMetadata().get("source"),
                    doc.getMetadata().get("category"),
                    doc.getMetadata().get(CrossEncoderRetrievalReranker.RERANK_SCORE_METADATA_KEY)));
        }
        // Async: refresh lastAccessedAt for memory docs (type=summary/reflection) that were hit.
        // RAG knowledge docs have no 'type' field and are silently skipped inside the updater.
        memoryAccessUpdater.updateAccessTime(limited);
        return limited;
    }

    /**
     * HyDE expansion is appropriate ONLY when the dense retriever is going to embed the
     * query directly. Short keywords, exact lookups (numbers / quoted phrases), and
     * metadata-scoped queries either don't need HyDE or get hurt by it (semantic drift).
     */
    private boolean shouldUseHyde(RetrievalStrategy strategy, RetrievalRequest request) {
        if (strategy != RetrievalStrategy.DENSE) {
            return false;
        }
        if (request.hasMetadataFilters()) {
            return false;
        }
        return !retrievalRouter.isShortQuery(request.getQuery())
                && !retrievalRouter.looksLikeExactLookup(request.getQuery());
    }

    private List<Document> applyRerankMinScore(List<Document> reranked) {
        if (rerankMinScore <= 0.0 || reranked.isEmpty()) {
            return reranked;
        }
        return reranked.stream()
                .filter(doc -> {
                    Object raw = doc.getMetadata().get(CrossEncoderRetrievalReranker.RERANK_SCORE_METADATA_KEY);
                    if (!(raw instanceof Number score)) {
                        // No rerank score (heuristic reranker, or rerank disabled) — keep it.
                        return true;
                    }
                    return score.doubleValue() >= rerankMinScore;
                })
                .toList();
    }

    private boolean shouldRerank(RetrievalRequest retrievalRequest) {
        return rerankEnabled && retrievalRequest.isRerankEnabled();
    }

    private RetrievalStrategy resolveStrategy(RetrievalRequest retrievalRequest) {
        return retrievalRouter.route(retrievalRequest);
    }

    private boolean shouldUseHybridSearch(RetrievalStrategy strategy) {
        return hybridEnabled && strategy == RetrievalStrategy.HYBRID;
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
