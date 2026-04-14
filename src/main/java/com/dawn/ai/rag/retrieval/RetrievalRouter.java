package com.dawn.ai.rag.retrieval;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class RetrievalRouter {

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+");

    public RetrievalStrategy route(RetrievalRequest request) {
        if (request.getStrategy() != null && request.getStrategy() != RetrievalStrategy.AUTO) {
            return request.getStrategy();
        }
        if (request.hasMetadataFilters()) {
            return RetrievalStrategy.DENSE;
        }

        List<String> tokens = tokenize(request.getQuery());
        boolean keywordLike = tokens.size() <= 3
                || request.getQuery().contains("\"")
                || request.getQuery().matches(".*\\d.*");
        return keywordLike ? RetrievalStrategy.HYBRID : RetrievalStrategy.DENSE;
    }

    private List<String> tokenize(String query) {
        return TOKEN_SPLITTER.splitAsStream(query.toLowerCase(Locale.ROOT))
                .filter(token -> !token.isBlank())
                .toList();
    }
}
