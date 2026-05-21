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
        // Metadata filter 只负责缩小候选集，不应短路策略选择：短句/关键词在
        // 限定了 topicId/category 的情况下仍然适合 BM25；只走 dense 反而容易
        // 召回"语义相似但业务无关"的 chunk。
        boolean keywordLike = isShortQuery(request.getQuery()) || looksLikeExactLookup(request.getQuery());
        return keywordLike ? RetrievalStrategy.HYBRID : RetrievalStrategy.DENSE;
    }

    /** 短关键词查询（≤3 tokens）更适合 BM25 关键词匹配。 */
    public boolean isShortQuery(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return tokenize(query).size() <= 3;
    }

    /** 引号短语 / 数字 / 错误码 等特征表明用户在做精确查找。 */
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
