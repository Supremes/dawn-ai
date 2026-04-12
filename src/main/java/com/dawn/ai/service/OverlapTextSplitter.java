package com.dawn.ai.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple whitespace-based splitter with configurable overlap.
 */
public class OverlapTextSplitter implements DocumentTransformer {

    private final int chunkSize;
    private final int chunkOverlap;

    public OverlapTextSplitter(int chunkSize, int chunkOverlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be >= 0 and < chunkSize");
        }
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> transformed = new ArrayList<>();
        for (Document document : documents) {
            transformed.addAll(split(document));
        }
        return transformed;
    }

    private List<Document> split(Document document) {
        String text = document.getText();
        if (text == null || text.isBlank()) {
            return List.of(document);
        }

        String[] tokens = text.trim().split("\\s+");
        if (tokens.length <= chunkSize) {
            Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
            metadata.put("parentDocumentId", document.getId());
            metadata.put("chunkIndex", 0);
            metadata.put("chunkCount", 1);
            return List.of(new Document(document.getId(), text.trim(), metadata));
        }

        int step = chunkSize - chunkOverlap;
        List<String> chunkTexts = new ArrayList<>();
        for (int start = 0; start < tokens.length; start += step) {
            int end = Math.min(start + chunkSize, tokens.length);
            chunkTexts.add(String.join(" ", List.of(tokens).subList(start, end)));
            if (end >= tokens.length) {
                break;
            }
        }

        List<Document> chunks = new ArrayList<>(chunkTexts.size());
        for (int index = 0; index < chunkTexts.size(); index++) {
            Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
            metadata.put("parentDocumentId", document.getId());
            metadata.put("chunkIndex", index);
            metadata.put("chunkCount", chunkTexts.size());
            chunks.add(new Document(document.getId() + "#chunk-" + index, chunkTexts.get(index), metadata));
        }
        return chunks;
    }
}
