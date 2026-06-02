package com.dawn.ai.rag.retrieval.sparse;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Okapi BM25 scorer.
 *
 * score(D, Q) = Σ IDF(qi) * (f(qi,D) * (k1 + 1)) / (f(qi,D) + k1 * (1 - b + b * |D| / avgdl))
 *
 * where:
 *   f(qi,D) = term frequency of qi in document D
 *   |D|     = document length (number of tokens)
 *   avgdl   = average document length in the corpus
 *   k1      = term frequency saturation parameter (default 1.2)
 *   b       = length normalization parameter (default 0.75)
 *   IDF(qi) = log((N - n(qi) + 0.5) / (n(qi) + 0.5) + 1)
 *   N       = total number of documents in the corpus
 *   n(qi)   = number of documents containing qi
 */
@Component
public class Bm25Scorer {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    /**
     * Compute BM25 scores for a set of documents against a query.
     *
     * @param queryTokens  tokenized query terms
     * @param documents    tokenized document terms (parallel with docLengths)
     * @return BM25 scores parallel to the documents list
     */
    public double[] score(List<String> queryTokens, List<List<String>> documents) {
        int n = documents.size();
        if (n == 0 || queryTokens.isEmpty()) {
            return new double[n];
        }

        // Compute document lengths and average
        int[] docLengths = new int[n];
        long totalLength = 0;
        for (int i = 0; i < n; i++) {
            docLengths[i] = documents.get(i).size();
            totalLength += docLengths[i];
        }
        double avgdl = (double) totalLength / n;
        if (avgdl == 0) {
            return new double[n];
        }

        // Build inverted index: term → set of doc indices containing it
        Map<String, int[]> termDocFreqs = new HashMap<>();
        for (int i = 0; i < n; i++) {
            for (String term : documents.get(i)) {
                termDocFreqs.computeIfAbsent(term, k -> new int[n])[i]++;
            }
        }

        // Compute BM25 score for each document
        double[] scores = new double[n];
        for (String queryTerm : queryTokens) {
            int[] freqs = termDocFreqs.get(queryTerm);
            if (freqs == null) continue;

            // Document frequency: number of documents containing this term
            int df = 0;
            for (int freq : freqs) {
                if (freq > 0) df++;
            }
            if (df == 0) continue;

            // IDF with floor to prevent negative values
            double idf = Math.log((n - df + 0.5) / (df + 0.5) + 1.0);

            for (int i = 0; i < n; i++) {
                int tf = freqs[i];
                if (tf == 0) continue;

                double numerator = tf * (K1 + 1);
                double denominator = tf + K1 * (1 - B + B * docLengths[i] / avgdl);
                scores[i] += idf * numerator / denominator;
            }
        }

        return scores;
    }
}
