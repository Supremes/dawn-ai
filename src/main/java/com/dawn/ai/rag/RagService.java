package com.dawn.ai.rag;

import com.dawn.ai.config.AiAvailabilityChecker;
import com.dawn.ai.memory.MemoryAccessUpdater;
import com.dawn.ai.rag.ingestion.OverlapTextSplitter;
import com.dawn.ai.rag.query.HydeQueryGenerator;
import com.dawn.ai.rag.query.QueryCategoryClassifier;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
    private final JdbcTemplate jdbcTemplate;
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
    private final QueryCategoryClassifier queryCategoryClassifier;

    public RagService(VectorStore vectorStore,
                      JdbcTemplate jdbcTemplate,
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
                      QueryRewriter queryRewriter,
                      QueryCategoryClassifier queryCategoryClassifier) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
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
        this.queryCategoryClassifier = queryCategoryClassifier;
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
     * cross-encoder rerank 分数下限：rerank 后 metadata 中 {@code rerankScore}
     * （由 {@link CrossEncoderRetrievalReranker} 写入）低于该阈值的文档会被丢弃。
     * 设为 0.0 关闭过滤。没有 rerankScore 的文档（如启用 heuristic reranker、
     * 或整个 rerank 被关闭）永远不会因此被过滤。
     */
    @Setter
    @Value("${app.ai.rag.reranker.min-score:0.0}")
    private double rerankMinScore = 0.0;

    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}")
    private String vectorStoreTable = "vector_store";

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

    public String ingest(String content, String source, String category, String topicId) {
        aiAvailabilityChecker.ensureConfigured();

        Document parentDoc = buildParentDoc(content, source, category, topicId);
        List<Document> chunks = splitter.apply(List.of(parentDoc));

        vectorStore.add(chunks);
        ingestionCounter.increment(chunks.size());

        log.info("[RagService] Ingested {} chunk(s), source={}, topicId={}", chunks.size(), source, topicId);
        return parentDoc.getId();
    }

    /**
     * Ingest multiple independent documents in one batch, sharing the same source/category/topicId.
     *
     * <p>Each entry becomes its own parent document (its own docId and chunk set), mirroring
     * {@link #ingest} per record. All chunks are split and written to the vector store in a single
     * pass so a large dataset costs only one {@code vectorStore.add} call.
     *
     * @return the generated docId of every ingested record, in input order
     */
    public List<String> ingestBatch(List<String> contents, String source, String category, String topicId) {
        aiAvailabilityChecker.ensureConfigured();

        List<Document> parentDocs = new ArrayList<>(contents.size());
        for (String content : contents) {
            parentDocs.add(buildParentDoc(content, source, category, topicId));
        }

        List<Document> chunks = splitter.apply(parentDocs);

        vectorStore.add(chunks);
        ingestionCounter.increment(chunks.size());

        List<String> docIds = parentDocs.stream().map(Document::getId).toList();
        log.info("[RagService] Batch ingested {} record(s), {} chunk(s), source={}, topicId={}",
                docIds.size(), chunks.size(), source, topicId);
        return docIds;
    }

    /**
     * Delete all chunks belonging to a parent document identified by docId.
     *
     * @return number of deleted chunks, or 0 if no chunks matched
     */
    public int deleteByDocId(String docId) {
        List<String> chunkIds = jdbcTemplate.queryForList(
                "SELECT id::text FROM " + vectorStoreTable() + " WHERE metadata->>'docId' = ?",
                String.class, docId);
        if (chunkIds.isEmpty()) {
            return 0;
        }
        vectorStore.delete(chunkIds);
        log.info("[RagService] Deleted {} chunk(s) for docId={}", chunkIds.size(), docId);
        return chunkIds.size();
    }

    /**
     * Update a document by deleting all existing chunks and re-ingesting with new content.
     *
     * @return the new docId assigned to the re-ingested document
     */
    public String updateDocument(String docId, String content, String source, String category, String topicId) {
        int deleted = deleteByDocId(docId);
        log.info("[RagService] Update: removed {} old chunk(s) for docId={}", deleted, docId);
        return ingest(content, source, category, topicId);
    }

    /**
     * List ingested documents grouped by docId, with optional filters.
     */
    public List<Map<String, Object>> listDocuments(String source, String category, String topicId,
                                                   int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT metadata->>'docId' as doc_id," +
                " metadata->>'source' as source," +
                " metadata->>'category' as category," +
                " metadata->>'topicId' as topic_id," +
                " COUNT(*) as chunk_count" +
                " FROM " + vectorStoreTable() +
                " WHERE metadata->>'docId' IS NOT NULL");
        List<Object> params = new ArrayList<>();

        if (source != null && !source.isBlank()) {
            sql.append(" AND metadata->>'source' = ?");
            params.add(source);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND metadata->>'category' = ?");
            params.add(category);
        }
        if (topicId != null && !topicId.isBlank()) {
            sql.append(" AND metadata->>'topicId' = ?");
            params.add(topicId);
        }

        sql.append(" GROUP BY doc_id, source, category, topic_id ORDER BY doc_id LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    private Document buildParentDoc(String content, String source, String category, String topicId) {
        String docId = UUID.randomUUID().toString();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", source != null ? source : "manual");
        metadata.put("category", category != null ? category : "general");
        metadata.put("docId", docId);
        metadata.put("ingestedAt", Instant.now().toString());
        if (topicId != null && !topicId.isBlank()) {
            metadata.put("topicId", topicId);
        }
        return new Document(docId, content, metadata);
    }

    /**
     * Retrieve top-K semantically similar documents for a query.
     *
     * Strategy:
     *  1. Request topK*2 candidates from vector store with similarityThreshold filter.
     *  2. Record how many candidates were filtered out (candidates - returned).
     *  3. Limit final result to topK.
     */
    public List<Document> retrieve(RetrievalRequest retrievalRequest) {
        aiAvailabilityChecker.ensureConfigured();

        int candidateCount = retrievalRequest.getTopK() * 2;

        // ── 查询变换流水线 ─────────────────────────────────────────
        // 1. 可选的 LLM rewrite：关键词归一化，去掉口语助词。
        // 2. 在改写后的 query 上做策略路由（短句/关键词 → HYBRID；其他 → DENSE）。
        // 3. 可选的 HyDE 扩写 —— 只在 dense 分支、且 query 是长自然语言时启用。
        //    短句 / 精确查找 / 带 metadata 过滤的场景一律跳过 HyDE，
        //    避免 embedding 空间漂移。
        String originalQuery = retrievalRequest.getQuery();
        // 可配置- LLM rewrite：关键词归一化，去掉口语助词。
        String rewrittenQuery = queryRewriter.rewrite(originalQuery);
        RetrievalRequest rewrittenRequest = rewrittenQuery.equals(originalQuery)
                ? retrievalRequest
                : retrievalRequest.toBuilder().query(rewrittenQuery).build();

        // 策略路由和 HyDE 判断基于 rewrittenRequest（用户显式 filter），
        // 不受后续 auto-classify 注入的 category filter 影响。
        RetrievalStrategy strategy = resolveStrategy(rewrittenRequest);

        // 可配置- LLM HyDE 扩写
        String denseQuery = shouldUseHyde(strategy, rewrittenRequest)
                ? hydeQueryGenerator.generate(rewrittenQuery)
                : rewrittenQuery;

        // 可配置 - LLM 语义分类 category
        RetrievalRequest effectiveRequest;
        if (!rewrittenRequest.getMetadataFilters().containsKey("category")
            && !rewrittenRequest.getMetadataFilters().containsKey("topicId")) {
            String classifiedCategory = queryCategoryClassifier.classify(rewrittenQuery);
            if (classifiedCategory != null) {
                Map<String, List<String>> enrichedFilters = new HashMap<>(rewrittenRequest.getMetadataFilters());
                enrichedFilters.put("category", List.of(classifiedCategory));
                effectiveRequest = rewrittenRequest.toBuilder().metadataFilters(enrichedFilters).build();
                log.info("[RagService] Auto-classified category='{}' for query='{}'", classifiedCategory, rewrittenQuery);
            } else {
                effectiveRequest = rewrittenRequest;
            }
        } else {
            effectiveRequest = rewrittenRequest;
        }

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
        // 稀疏检索使用改写后的 query（关键词友好），而非 HyDE 扩写的段落 ——
        // BM25 偏好简短的关键词 token，不需要一整段假设性回答。
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
     * HyDE 扩写仅适用于 dense 检索直接对 query 做 embedding 的场景。
     * 短关键词、精确查找（数字/引号短语）、带 metadata filter 的 query 要么不需要
     * HyDE，要么会被它伤到（语义漂移）。
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
                        // 没有 rerank score（heuristic reranker 或 rerank 被关闭）—— 保留。
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

    private String vectorStoreTable() {
        return VectorStoreTableName.requireValid(vectorStoreTable);
    }
}
