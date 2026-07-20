package com.dawn.ai.evaluation.base;

import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class EvaluationReport {

    private final List<JudgeResult> results = new ArrayList<>();
    private final List<EvaluationCaseResult> caseResults = new ArrayList<>();

    public void add(JudgeResult result) {
        results.add(result);
    }

    public List<JudgeResult> results() {
        return List.copyOf(results);
    }

    public void addCaseResult(EvaluationCaseResult result) {
        caseResults.add(result);
    }

    public List<EvaluationCaseResult> caseResults() {
        return List.copyOf(caseResults);
    }

    public Map<JudgeDimension, List<JudgeResult>> byDimension() {
        return results.stream().collect(Collectors.groupingBy(JudgeResult::dimension));
    }

    public double averageScore() {
        return results.stream().mapToDouble(JudgeResult::score).average().orElse(0.0);
    }

    public double passRate() {
        if (results.isEmpty()) return 0.0;
        return (double) results.stream().filter(JudgeResult::passed).count() / results.size();
    }

    public Map<JudgeDimension, DoubleSummaryStatistics> dimensionStats() {
        return results.stream().collect(
                Collectors.groupingBy(JudgeResult::dimension,
                        Collectors.summarizingDouble(JudgeResult::score))
        );
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Evaluation Report ===\n");
        sb.append(String.format("Total: %d cases, Pass rate: %.1f%%, Avg score: %.2f\n",
                results.size(), passRate() * 100, averageScore()));

        for (var entry : dimensionStats().entrySet()) {
            DoubleSummaryStatistics stats = entry.getValue();
            sb.append(String.format("  %s: avg=%.2f, min=%.1f, max=%.1f, count=%d\n",
                    entry.getKey().id(), stats.getAverage(), stats.getMin(), stats.getMax(), stats.getCount()));
        }
        return sb.toString();
    }
}
