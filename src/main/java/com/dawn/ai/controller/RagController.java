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
     * 把一篇文档 ingest 进向量知识库。
     */
    @PostMapping(value = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> ingest(@Valid @RequestBody RagRequest request) {
        String docId = ragService.ingest(request.getContent(), request.getSource(), request.getCategory(), request.getTopicId());
        return ResponseEntity.ok(Map.of("docId", docId, "status", "ingested"));
    }

    /**
     * 通过文件上传 ingest 多种类型的文档（text/pdf/word/excel）。
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

        // JSONL 是按记录组织的：每一行都是一篇独立文档，各自有自己的 docId。
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
     * 根据给定 query 检索语义相似的文档。
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
     * 删除某个父文档下的所有 chunk。
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
     * 更新文档：用新内容替换掉它的全部 chunk。
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
     * 列出已 ingest 的文档，按 docId 分组，支持可选的过滤条件。
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

        // JSONL 必须先于 TEXT 判断：上传的 .jsonl 文件经常带着 text/* 的 content type，
        // 不先判断的话就会被误当成一篇单独的 TEXT 文档。
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
