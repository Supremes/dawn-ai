package com.dawn.ai.service;

import com.dawn.ai.config.AiAvailabilityChecker;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TextChunker;
import io.agentscope.core.rag.reader.TextReader;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

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
    private final Knowledge knowledge;

    @Setter
    @Value("${app.ai.rag.similarity-threshold:0.7}")
    private double similarityThreshold;

    @Setter
    @Value("${app.ai.rag.default-top-k:5}")
    private int defaultTopK;

    private Counter ingestionCounter;
    private Counter retrievalHitCounter;
    private Counter retrievalMissCounter;
    private DistributionSummary filteredCountSummary;
    private TokenTextSplitter splitter;

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
        splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(350)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();
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

    public void ingestToAgentScope(String content) {
        aiAvailabilityChecker.ensureConfigured();

        TextReader textReader = new TextReader(500, SplitStrategy.PARAGRAPH, 50);
        List<io.agentscope.core.rag.model.Document> documents = textReader.read(ReaderInput.fromString(content)).block();
        knowledge.addDocuments(documents).block();
        assert documents != null;
        ingestionCounter.increment(documents.size());
        log.info("ingest documents to vector store, documents={}", documents);
    }

    public List<io.agentscope.core.rag.model.Document> retrieveFromAgentScope(String query) {
        aiAvailabilityChecker.ensureConfigured();

        RetrieveConfig config = RetrieveConfig.builder()
                .scoreThreshold(similarityThreshold)
                .limit(defaultTopK)
                .build();
        List<io.agentscope.core.rag.model.Document> documentList = knowledge.retrieve(query, config).block();
        log.info("[RagService] Retrieved {}/{} docs (threshold={}), query='{}'",
                documentList.size(), defaultTopK, similarityThreshold, query);
        return  documentList;
    }

    public void ingestToAgentScope(String content, String source, String category) {
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
        log.info("ingest documents to vector store, documents={}", documents);
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
        aiAvailabilityChecker.ensureConfigured();

        int candidateCount = topK * 2;
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(candidateCount)
                .similarityThreshold(similarityThreshold)
                .build();

        List<Document> results = vectorStore.similaritySearch(request);
        int filteredOut = Math.max(0, candidateCount - results.size());
        filteredCountSummary.record(filteredOut);

        if (results.isEmpty()) {
            retrievalMissCounter.increment();
        } else {
            retrievalHitCounter.increment();
        }

        List<Document> limited = results.stream().limit(topK).toList();
        log.info("[RagService] Retrieved {}/{} docs (threshold={}, filtered={}), query='{}'",
                limited.size(), candidateCount, similarityThreshold, filteredOut, query);
        return limited;
    }
}
