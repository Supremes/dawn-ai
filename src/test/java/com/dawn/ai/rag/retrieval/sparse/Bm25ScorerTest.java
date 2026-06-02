package com.dawn.ai.rag.retrieval.sparse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class Bm25ScorerTest {

    private Bm25Scorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new Bm25Scorer();
    }

    @Test
    @DisplayName("空文档列表返回空分数数组")
    void score_emptyDocs_returnsEmptyScores() {
        double[] scores = scorer.score(List.of("query"), List.of());
        assertThat(scores).isEmpty();
    }

    @Test
    @DisplayName("空 query tokens 返回全零分数")
    void score_emptyQuery_returnsZeroScores() {
        List<List<String>> docs = List.of(
                List.of("hello", "world"),
                List.of("foo", "bar"));
        double[] scores = scorer.score(List.of(), docs);
        assertThat(scores).containsExactly(0.0, 0.0);
    }

    @Test
    @DisplayName("包含 query term 的文档得分高于不包含的")
    void score_relevantDocScoresHigher() {
        List<List<String>> docs = List.of(
                List.of("java", "spring", "boot"),      // contains "java"
                List.of("python", "django", "flask"),    // no match
                List.of("java", "microservice", "cloud") // contains "java"
        );
        double[] scores = scorer.score(List.of("java"), docs);

        assertThat(scores[0]).isGreaterThan(0.0);
        assertThat(scores[1]).isEqualTo(0.0);
        assertThat(scores[2]).isGreaterThan(0.0);
        // Both "java" docs should have equal score (same tf, same df, same doc length)
        assertThat(scores[0]).isCloseTo(scores[2], within(0.001));
    }

    @Test
    @DisplayName("词频更高的文档得分更高（TF 饱和）")
    void score_higherTfScoresHigher() {
        List<List<String>> docs = List.of(
                List.of("java"),                         // tf=1, short doc
                List.of("java", "java", "java", "java")  // tf=4, longer doc
        );
        double[] scores = scorer.score(List.of("java"), docs);

        // Higher TF should score higher, but BM25 has saturation
        assertThat(scores[1]).isGreaterThan(scores[0]);
    }

    @Test
    @DisplayName("较短文档在同等 TF 下得分更高（文档长度归一化）")
    void score_shorterDocScoresHigherWithSameTf() {
        List<List<String>> docs = List.of(
                List.of("java", "spring"),                     // tf=1, len=2
                List.of("java", "spring", "boot", "web", "mvc") // tf=1, len=5
        );
        double[] scores = scorer.score(List.of("java"), docs);

        // Shorter doc should score higher due to length normalization (b=0.75)
        assertThat(scores[0]).isGreaterThan(scores[1]);
    }

    @Test
    @DisplayName("多查询词累加得分")
    void score_multipleQueryTermsCumulative() {
        List<List<String>> docs = List.of(
                List.of("java", "spring", "boot"),
                List.of("java", "python")
        );
        double[] scoresMulti = scorer.score(List.of("java", "spring"), docs);
        double[] scoresSingle = scorer.score(List.of("java"), docs);

        // Doc 0 has both "java" and "spring", so multi-query score should be higher
        assertThat(scoresMulti[0]).isGreaterThan(scoresSingle[0]);
        // Doc 1 only has "java", so multi-query score should equal single
        assertThat(scoresMulti[1]).isCloseTo(scoresSingle[1], within(0.001));
    }

    @Test
    @DisplayName("BM25 公式验证：IDF 加权正确")
    void score_idfWeightingCorrect() {
        // "rare" appears in 1 doc, "common" appears in 3 docs
        List<List<String>> docs = List.of(
                List.of("rare"),
                List.of("common", "word"),
                List.of("common", "other"),
                List.of("common", "stuff")
        );
        double[] rareScores = scorer.score(List.of("rare"), docs);
        double[] commonScores = scorer.score(List.of("common"), docs);

        // "rare" term should have higher IDF → higher score for its single document
        assertThat(rareScores[0]).isGreaterThan(commonScores[1]);
    }
}
