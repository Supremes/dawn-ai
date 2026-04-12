package com.dawn.ai.rag.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
public class HeuristicRetrievalReranker implements RetrievalReranker {

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+");

    @Override
    public List<Document> rerank(RetrievalRequest request, List<Document> candidates) {
        if (candidates.size() < 2) {
            return candidates;
        }

        List<String> queryTokens = tokenize(request.getQuery());
        if (queryTokens.isEmpty()) {
            return candidates;
        }

        return IntStream.range(0, candidates.size())
                .mapToObj(index -> new ScoredDocument(
                        candidates.get(index),
                        score(request, candidates.get(index), queryTokens, index)))
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                .map(ScoredDocument::document)
                .toList();
    }

    private double score(
            RetrievalRequest request,
            Document document,
            List<String> queryTokens,
            int originalIndex) {
        String documentText = document.getText() == null ? "" : document.getText().toLowerCase(Locale.ROOT);
        Set<String> documentTokens = tokenize(documentText).stream().collect(Collectors.toSet());
        long matchedTokens = queryTokens.stream().filter(documentTokens::contains).count();

        double lexicalCoverage = (double) matchedTokens / queryTokens.size();
        double phraseBoost = documentText.contains(request.getQuery().toLowerCase(Locale.ROOT)) ? 2.0 : 0.0;
        double metadataBoost = metadataBoost(document.getMetadata(), request.getMetadataFilters());
        double originalRankBoost = 1.0 / (originalIndex + 1);

        return phraseBoost + (lexicalCoverage * 3.0) + metadataBoost + originalRankBoost;
    }

    private double metadataBoost(Map<String, Object> metadata, Map<String, List<String>> filters) {
        if (metadata == null || metadata.isEmpty() || filters == null || filters.isEmpty()) {
            return 0.0;
        }

        return filters.entrySet().stream()
                .filter(entry -> {
                    Object actual = metadata.get(entry.getKey());
                    return actual != null && entry.getValue().contains(String.valueOf(actual));
                })
                .count() * 0.5;
    }

    private List<String> tokenize(String text) {
        return TOKEN_SPLITTER.splitAsStream(text.toLowerCase(Locale.ROOT))
                .filter(token -> !token.isBlank())
                .toList();
    }

    private record ScoredDocument(Document document, double score) {}
}
