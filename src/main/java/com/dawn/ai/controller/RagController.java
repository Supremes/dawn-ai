package com.dawn.ai.controller;

import com.dawn.ai.dto.RagRequest;
import com.dawn.ai.rag.RagService;
import com.dawn.ai.rag.constants.DocumentType;
import com.dawn.ai.rag.ingestion.DocumentTextExtractor;
import com.dawn.ai.rag.query.QueryCategoryClassifier;
import com.dawn.ai.rag.retrieval.RetrievalRequest;
import com.dawn.ai.rag.retrieval.RetrievalStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;
    private final DocumentTextExtractor documentTextExtractor;
    private final QueryCategoryClassifier queryCategoryClassifier;

    /**
     * Ingest a document into the vector knowledge base.
     */
    @PostMapping(value = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> ingest(@Valid @RequestBody RagRequest request) {
        String docId = ragService.ingest(request.getContent(), request.getSource(), request.getCategory(), request.getTopicId());
        return ResponseEntity.ok(Map.of("docId", docId, "status", "ingested"));
    }

    /**
     * Ingest file uploads for multiple document types (text/pdf/word/excel).
     */
    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> ingestFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) DocumentType documentType,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String topicId,
            @RequestParam(required = false) String textField) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }

        DocumentType resolvedType = documentType != null ? documentType : inferDocumentType(file);
        String effectiveSource = (source != null && !source.isBlank()) ? source : file.getOriginalFilename();

        // JSONL is record-oriented: one independent document per line, each with its own docId.
        if (resolvedType == DocumentType.JSONL) {
            List<String> records = documentTextExtractor.extractJsonlRecords(file, textField);
            List<String> docIds = ragService.ingestBatch(records, effectiveSource, category, topicId);
            return ResponseEntity.ok(Map.of(
                    "status", "ingested",
                    "documentType", resolvedType.name(),
                    "recordCount", String.valueOf(docIds.size())
            ));
        }

        String content = documentTextExtractor.extract(file, resolvedType);
        String docId = ragService.ingest(content, effectiveSource, category, topicId);
        return ResponseEntity.ok(Map.of(
                "docId", docId,
                "status", "ingested",
                "documentType", resolvedType.name()
        ));
    }

    /**
     * Retrieve semantically similar documents for a given query.
     */
    @GetMapping("/search")
    public ResponseEntity<List<Document>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") @Min(1) @Max(value = 20, message = "must be less than or equal to 20") int topK,
            @RequestParam(required = false) List<String> source,
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false, name = "docId") List<String> docIds,
            @RequestParam(required = false) List<String> topicId,
            @RequestParam(defaultValue = "AUTO") RetrievalStrategy strategy) {
        RetrievalRequest request = RetrievalRequest.builder()
                .query(query)
                .topK(topK)
                .strategy(strategy)
                .metadataFilters(buildMetadataFilters(source, category, docIds, topicId))
                .build();
        List<Document> results = ragService.retrieve(request);
        return ResponseEntity.ok(results);
    }

    /**
     * Delete all chunks belonging to a parent document.
     */
    @DeleteMapping("/documents/{docId}")
    public ResponseEntity<Map<String, Object>> deleteDocument(@PathVariable String docId) {
        int deleted = ragService.deleteByDocId(docId);
        if (deleted == 0) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "not_found",
                    "message", "No chunks found for docId: " + docId));
        }
        return ResponseEntity.ok(Map.of("docId", docId, "deletedChunks", deleted));
    }

    /**
     * Update a document by replacing all its chunks with new content.
     */
    @PutMapping("/documents/{docId}")
    public ResponseEntity<Map<String, Object>> updateDocument(
            @PathVariable String docId,
            @Valid @RequestBody RagRequest request) {
        String newDocId = ragService.updateDocument(
                docId, request.getContent(), request.getSource(),
                request.getCategory(), request.getTopicId());
        return ResponseEntity.ok(Map.of(
                "oldDocId", docId,
                "newDocId", newDocId,
                "status", "updated"));
    }

    /**
     * List ingested documents grouped by docId, with optional filters.
     */
    @GetMapping("/documents")
    public ResponseEntity<List<Map<String, Object>>> listDocuments(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String topicId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        List<Map<String, Object>> docs = ragService.listDocuments(source, category, topicId, limit, offset);
        return ResponseEntity.ok(docs);
    }

    @GetMapping("/categories")
    public ResponseEntity<List<String>> getCategories() {
        return ResponseEntity.ok(queryCategoryClassifier.getCategories());
    }

    private Map<String, List<String>> buildMetadataFilters(
            List<String> source,
            List<String> category,
            List<String> docIds,
            List<String> topicId) {
        Map<String, List<String>> filters = new LinkedHashMap<>();
        addFilter(filters, "source", source);
        addFilter(filters, "category", category);
        addFilter(filters, "docId", docIds);
        addFilter(filters, "topicId", topicId);
        return filters;
    }

    private void addFilter(Map<String, List<String>> filters, String key, List<String> values) {
        if (values != null && !values.isEmpty()) {
            filters.put(key, values);
        }
    }

    private DocumentType inferDocumentType(MultipartFile file) {
        String filename = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase(Locale.ROOT)
                : "";
        String contentType = file.getContentType() != null
                ? file.getContentType().toLowerCase(Locale.ROOT)
                : "";

        // JSONL must be checked before TEXT: a .jsonl upload often carries a text/* content
        // type, which would otherwise be misclassified as a single TEXT document.
        if (isJsonlDocument(filename, contentType)) {
            return DocumentType.JSONL;
        }
        if (isTextDocument(filename, contentType)) {
            return DocumentType.TEXT;
        }
        if (isPdfDocument(filename, contentType)) {
            return DocumentType.PDF;
        }
        if (isWordDocument(filename, contentType)) {
            return DocumentType.WORD;
        }
        if (isExcelDocument(filename, contentType)) {
            return DocumentType.EXCEL;
        }

        throw new IllegalArgumentException("Unsupported file format. Supported: text/pdf/word/excel/jsonl");
    }

    private boolean isJsonlDocument(String filename, String contentType) {
        return filename.endsWith(".jsonl")
                || filename.endsWith(".ndjson")
                || "application/jsonl".equals(contentType)
                || "application/x-ndjson".equals(contentType)
                || "application/x-jsonlines".equals(contentType);
    }

    private boolean isTextDocument(String filename, String contentType) {
        return filename.endsWith(".txt")
                || filename.endsWith(".md")
                || filename.endsWith(".csv")
                || contentType.startsWith("text/");
    }

    private boolean isPdfDocument(String filename, String contentType) {
        return filename.endsWith(".pdf") || "application/pdf".equals(contentType);
    }

    private boolean isWordDocument(String filename, String contentType) {
        return filename.endsWith(".doc")
                || filename.endsWith(".docx")
                || "application/msword".equals(contentType)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(contentType);
    }

    private boolean isExcelDocument(String filename, String contentType) {
        return filename.endsWith(".xls")
                || filename.endsWith(".xlsx")
                || "application/vnd.ms-excel".equals(contentType)
                || "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(contentType);
    }
}
