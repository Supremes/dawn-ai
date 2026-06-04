package com.dawn.ai.rag.retrieval.sparse;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Chinese text tokenizer backed by jieba word segmentation.
 *
 * Produces lowercase tokens, filtering out single-character punctuation and whitespace.
 * Pure ASCII tokens (English words, numbers) are kept as-is after lowercasing.
 */
@Component
public class ChineseTokenizer {

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    /**
     * Tokenize text into individual terms suitable for BM25 indexing / querying.
     */
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return segmenter.process(text, JiebaSegmenter.SegMode.SEARCH)
                .stream()
                .map(t -> t.word)
                .map(word -> word.toLowerCase(Locale.ROOT))
                .filter(word -> !word.isBlank() && (word.length() > 1 || isCjk(word.charAt(0))))
                .toList();
    }

    private boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }
}
