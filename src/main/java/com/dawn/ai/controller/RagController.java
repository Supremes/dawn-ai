package com.dawn.ai.controller;

import com.dawn.ai.dto.RagRequest;
import com.dawn.ai.rag.RagService;
import com.dawn.ai.rag.constants.DocumentType;
import com.dawn.ai.rag.ingestion.DocumentTextExtractor;
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

    /**
     * Ingest a document into the vector knowledge base.
     */
    @PostMapping(value = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> ingest(@Valid @RequestBody RagRequest request) {
        String docId = ragService.ingest(request.getContent(), request.getSource(), request.getCategory());
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
            @RequestParam(required = false) String category) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }

        DocumentType resolvedType = documentType != null ? documentType : inferDocumentType(file);
        String content = documentTextExtractor.extract(file, resolvedType);
        String effectiveSource = (source != null && !source.isBlank()) ? source : file.getOriginalFilename();
        String docId = ragService.ingest(content, effectiveSource, category);
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
            @RequestParam(defaultValue = "AUTO") RetrievalStrategy strategy) {
        RetrievalRequest request = RetrievalRequest.builder()
                .query(query)
                .topK(topK)
                .strategy(strategy)
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

    private DocumentType inferDocumentType(MultipartFile file) {
        String filename = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase(Locale.ROOT)
                : "";
        String contentType = file.getContentType() != null
                ? file.getContentType().toLowerCase(Locale.ROOT)
                : "";

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

        throw new IllegalArgumentException("Unsupported file format. Supported: text/pdf/word/excel");
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
