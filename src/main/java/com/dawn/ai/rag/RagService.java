package com.dawn.ai.rag;

import com.dawn.ai.config.AiAvailabilityChecker;
import com.dawn.ai.rag.ingestion.OverlapTextSplitter;
import com.dawn.ai.rag.retrieval.fusion.ReciprocalRankFusion;
import com.dawn.ai.rag.retrieval.RetrievalRequest;
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

    public RagService(VectorStore vectorStore,
                      MeterRegistry meterRegistry,
                      AiAvailabilityChecker aiAvailabilityChecker,
                      RetrievalReranker retrievalReranker,
                      SparseRetriever sparseRetriever,
                      ReciprocalRankFusion reciprocalRankFusion,
                      RetrievalRouter retrievalRouter,
                      DocumentTransformer splitter,
                      @Qualifier("ragRetrievalExecutor") ExecutorService ragRetrievalExecutor) {
        this.vectorStore = vectorStore;
        this.meterRegistry = meterRegistry;
        this.aiAvailabilityChecker = aiAvailabilityChecker;
        this.retrievalReranker = retrievalReranker;
        this.sparseRetriever = sparseRetriever;
        this.reciprocalRankFusion = reciprocalRankFusion;
        this.retrievalRouter = retrievalRouter;
        this.splitter = splitter;
        this.ragRetrievalExecutor = ragRetrievalExecutor;
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

        RetrievalStrategy strategy = resolveStrategy(retrievalRequest);
        CompletableFuture<List<Document>> denseFuture = CompletableFuture.supplyAsync(
            () -> vectorStore.similaritySearch(request),
            ragRetrievalExecutor);
        CompletableFuture<List<Document>> sparseFuture = CompletableFuture.supplyAsync(() -> shouldUseHybridSearch(strategy)
                ? sparseRetriever.retrieve(retrievalRequest, candidateCount)
                : List.of(),
            ragRetrievalExecutor);

        CompletableFuture.allOf(denseFuture, sparseFuture).join();

        List<Document> denseResults = denseFuture.join();
        List<Document> sparseResults = sparseFuture.join();
        log.debug("[RagService] Retrieval candidates: dense={}, sparse={}, strategy={}, query='{}'",
            denseResults.size(), sparseResults.size(), strategy, retrievalRequest.getQuery());
        List<Document> results = shouldUseHybridSearch(strategy)
            ? reciprocalRankFusion.fuse(denseResults, sparseResults)
            : denseResults;

        int filteredOut = Math.max(0, candidateCount - denseResults.size());
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
        log.info("[RagService] Retrieved {}/{} docs (strategy={}, threshold={}, filtered={}), query='{}', metadataFilters={}",
                limited.size(), candidateCount, strategy, similarityThreshold, filteredOut,
                retrievalRequest.getQuery(), retrievalRequest.getMetadataFilters());
        return limited;
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
