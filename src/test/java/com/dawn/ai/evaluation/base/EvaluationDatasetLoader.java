package com.dawn.ai.evaluation.base;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class EvaluationDatasetLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static List<EvaluationCase> loadAll() {
        return load("evaluation/evaluation-dataset.json");
    }

    public static List<EvaluationCase> loadByDimension(String dimension) {
        return loadAll().stream()
                .filter(c -> dimension.equals(c.dimension()))
                .toList();
    }

    private static List<EvaluationCase> load(String path) {
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            return MAPPER.readValue(is, new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load evaluation dataset: " + path, e);
        }
    }
}
