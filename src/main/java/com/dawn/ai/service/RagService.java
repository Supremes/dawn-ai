package com.dawn.ai.service;

import com.dawn.ai.config.AiAvailabilityChecker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RAG (Retrieval Augmented Generation) Service.
 *
 * Design Analogy:
 * Vector similarity search here is conceptually like MySQL's B-Tree index lookup,
 * but instead of comparing scalar values, we compute cosine similarity between
 * high-dimensional embedding vectors — like a skiplist in Redis finding the
 * nearest neighbor in O(log N) space.
 *
 * Pipeline: Document → Chunk → Embed → Store → Query → Augment Prompt → Generate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final VectorStore vectorStore;
    private final MeterRegistry meterRegistry;
    private final AiAvailabilityChecker aiAvailabilityChecker;

    private Counter ingestionCounter;
    private Counter retrievalHitCounter;
    private Counter retrievalMissCounter;

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
    }

    /**
     * Ingest a document chunk into the vector store.
     * In production: split large docs using TokenTextSplitter before ingestion.
     */
    public String ingest(String content, String source, String category) {
        aiAvailabilityChecker.ensureConfigured();

        String docId = UUID.randomUUID().toString();
        Document doc = new Document(docId, content, Map.of(
                "source", source != null ? source : "manual",
                "category", category != null ? category : "general"
        ));
        vectorStore.add(List.of(doc));
        ingestionCounter.increment();
        log.info("[RagService] Ingested document id={}, source={}", docId, source);
        return docId;
    }

    /**
     * Retrieve top-K semantically similar documents for a query.
     * TopK=5 is the default; tune based on context window budget.
     */
    public List<Document> retrieve(String query, int topK) {
        aiAvailabilityChecker.ensureConfigured();

        SearchRequest request = SearchRequest.builder().query(query).topK(topK).build();
        List<Document> results = vectorStore.similaritySearch(request);

        if (results.isEmpty()) {
            retrievalMissCounter.increment();
        } else {
            retrievalHitCounter.increment();
        }

        log.info("[RagService] Retrieved {} docs for query='{}'", results.size(), query);
        return results;
    }

    /** Build an augmented context string from retrieved documents */
    public String buildContext(String query) {
        List<Document> docs = retrieve(query, 5);
        if (docs.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("Relevant context:\n");
        for (int i = 0; i < docs.size(); i++) {
            sb.append(String.format("[%d] %s\n", i + 1, docs.get(i).getText()));
        }
        return sb.toString();
    }
}
