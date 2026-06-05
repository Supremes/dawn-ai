package com.dawn.ai.evaluation.judge;

public record JudgeResult(
        JudgeDimension dimension,
        double score,
        String reasoning
) {
    public boolean passed() {
        return switch (dimension.scoreType()) {
            case BINARY -> score >= 1.0;
            case LIKERT -> score >= 3.5;
        };
    }
}
