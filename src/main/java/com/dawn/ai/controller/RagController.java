package com.dawn.ai.controller;

import com.dawn.ai.dto.RagRequest;
import com.dawn.ai.service.RagService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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
            @RequestParam(defaultValue = "5") @Min(1) @Max(20) int topK) {
        List<Document> results = ragService.retrieve(query, topK);
        return ResponseEntity.ok(results);
    }

    /**
     * Ingest a document into the vector knowledge base.
     */
    @PostMapping("/ingest/agentscope")
    public ResponseEntity<Map<String, String>> ingestAgentScope(@Valid @RequestBody RagRequest request) {
        ragService.ingestToAgentScope(request.getContent(), request.getSource(), request.getCategory());
        return ResponseEntity.ok(Map.of("status", "ingested"));
    }

    /**
     * Retrieve semantically similar documents for a given query.
     */
    @GetMapping("/search/agentscope")
    public ResponseEntity<List<io.agentscope.core.rag.model.Document>> searchAgentScope(
            @RequestParam String query) {
        List<io.agentscope.core.rag.model.Document> results = ragService.retrieveFromAgentScope(query);
        return ResponseEntity.ok(results);
    }
}
