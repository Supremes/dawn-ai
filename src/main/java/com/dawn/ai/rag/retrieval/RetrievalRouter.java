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
        // Heuristic: it's likely keyword-based
        // - 短查询（通常是核心名词）更适合关键词搜索
        // - 如果用户使用了引号，通常代表精确匹配需求
        // - 包含数字（如型号、日期、ID）。向量模型对精确数字的敏感度通常不如传统的关键词检索
        boolean keywordLike = tokens.size() <= 3
                || request.getQuery().contains("\"")
                || request.getQuery().matches(".*\\d.*");
        return keywordLike ? RetrievalStrategy.HYBRID : RetrievalStrategy.DENSE;
    }

    /*
     * 预处理分词：
     * 1. 将查询字符串转换为小写
     * 2. 使用预定义的正则表达式将查询字符串拆分为单词列表
     * 3. 过滤掉空白或无效的单词
     * 4. 返回处理后的单词列表
     */
    private List<String> tokenize(String query) {
        return TOKEN_SPLITTER.splitAsStream(query.toLowerCase(Locale.ROOT))
                .filter(token -> !token.isBlank())
                .toList();
    }
}
