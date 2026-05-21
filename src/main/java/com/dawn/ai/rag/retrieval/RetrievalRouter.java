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
        // Metadata filters narrow the candidate set; they should NOT short-circuit
        // strategy selection. Short / keyword queries still benefit from BM25 even
        // when scoped by topicId/category — falling back to dense alone risks
        // semantically-similar-but-business-irrelevant chunks.
        boolean keywordLike = isShortQuery(request.getQuery()) || looksLikeExactLookup(request.getQuery());
        return keywordLike ? RetrievalStrategy.HYBRID : RetrievalStrategy.DENSE;
    }

    /** Short keyword-like queries (≤3 tokens) benefit from BM25 keyword matching. */
    public boolean isShortQuery(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return tokenize(query).size() <= 3;
    }

    /** Quoted phrases / digits / error codes signal a precise lookup. */
    public boolean looksLikeExactLookup(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return query.contains("\"") || query.matches(".*\\d.*");
    }

    /*
     * 预处理分词：
     * 1. 将查询字符串转换为小写
     * 2. 使用预定义的正则表达式将查询字符串拆分为单词列表
     * 3. 过滤掉空白或无效的单词
     * 4. 返回处理后的单词列表
     */
    private List<String> tokenize(String query) {
        if (query == null) {
            return List.of();
        }
        return TOKEN_SPLITTER.splitAsStream(query.toLowerCase(Locale.ROOT))
                .filter(token -> !token.isBlank())
                .toList();
    }
}
