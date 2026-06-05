package com.dawn.ai.evaluation.base;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluationCase(
        String id,
        String dimension,
        String query,
        Map<String, Object> context,
        Expected expected
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Expected(
            List<String> tools,
            String answerCriteria,
            List<String> docIds
        ) {}
}
