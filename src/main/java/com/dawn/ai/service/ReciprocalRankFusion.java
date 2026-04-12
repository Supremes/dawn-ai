package com.dawn.ai.service;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ReciprocalRankFusion {

    private static final int RANK_CONSTANT = 60;

    public List<Document> fuse(List<Document> dense, List<Document> sparse) {
        Map<String, ScoredDocument> fused = new LinkedHashMap<>();
        addScores(fused, dense);
        addScores(fused, sparse);

        return fused.values().stream()
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                .map(ScoredDocument::document)
                .toList();
    }

    private void addScores(Map<String, ScoredDocument> fused, List<Document> documents) {
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            String key = identityOf(document);
            double additionalScore = 1.0 / (RANK_CONSTANT + index + 1);
            fused.merge(key,
                    new ScoredDocument(document, additionalScore),
                    (left, right) -> new ScoredDocument(left.document(), left.score() + right.score()));
        }
    }

    private String identityOf(Document document) {
        return (document.getId() != null && !document.getId().isBlank())
                ? document.getId()
                : document.getText();
    }

    private record ScoredDocument(Document document, double score) {}
}
