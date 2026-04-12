package com.dawn.ai.controller;

import com.dawn.ai.dto.RagRequest;
import com.dawn.ai.service.RagService;
import com.dawn.ai.service.RetrievalRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    /**
     * Ingest a document into the vector knowledge base.
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, String>> ingest(@Valid @RequestBody RagRequest request) {
        String docId = ragService.ingest(request.getContent(), request.getSource(), request.getCategory());
        return ResponseEntity.ok(Map.of("docId", docId, "status", "ingested"));
    }

    /**
     * Retrieve semantically similar documents for a given query.
     */
    @GetMapping("/search")
    public ResponseEntity<List<Document>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int topK,
            @RequestParam(required = false) List<String> source,
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false, name = "docId") List<String> docIds) {
        RetrievalRequest request = RetrievalRequest.builder()
                .query(query)
                .topK(topK)
                .metadataFilters(buildMetadataFilters(source, category, docIds))
                .build();
        List<Document> results = ragService.retrieve(request);
        return ResponseEntity.ok(results);
    }

    private Map<String, List<String>> buildMetadataFilters(
            List<String> source,
            List<String> category,
            List<String> docIds) {
        Map<String, List<String>> filters = new LinkedHashMap<>();
        addFilter(filters, "source", source);
        addFilter(filters, "category", category);
        addFilter(filters, "docId", docIds);
        return filters;
    }

    private void addFilter(Map<String, List<String>> filters, String key, List<String> values) {
        if (values != null && !values.isEmpty()) {
            filters.put(key, values);
        }
    }
}
