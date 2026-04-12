package com.dawn.ai.rag.retrieval.rerank;

import java.util.Locale;

public enum RetrievalRerankerType {

    HEURISTIC,
    CROSS_ENCODER;

    public static RetrievalRerankerType from(String value) {
        if (value == null || value.isBlank()) {
            return HEURISTIC;
        }

        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        for (RetrievalRerankerType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return HEURISTIC;
    }
}