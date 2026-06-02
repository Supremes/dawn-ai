package com.dawn.ai.evaluation.base;

import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Langfuse REST API 客户端，用于写入评分和同步 Dataset。
 * 通过 LANGFUSE_* 环境变量配置，默认使用 docker-compose.observe.yml 中的凭据。
 */
@Component
public class LangfuseScoringClient {

    private static final Logger log = LoggerFactory.getLogger(LangfuseScoringClient.class);

    private final RestClient restClient;
    private final String projectId;

    public LangfuseScoringClient(
            @Value("${LANGFUSE_BASE_URL:http://localhost:3001}") String baseUrl,
            @Value("${LANGFUSE_PUBLIC_KEY:pk-lf-dawn-dev}") String publicKey,
            @Value("${LANGFUSE_SECRET_KEY:sk-lf-dawn-dev}") String secretKey,
            @Value("${LANGFUSE_INIT_PROJECT_ID:dawn-ai}") String projectId) {

        String auth = Base64.getEncoder().encodeToString(
                (publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Basic " + auth)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        this.projectId = projectId;
    }

    /**
     * 给 trace 打分
     */
    public void score(String traceId, JudgeResult result) {
        String name = result.dimension().id();
        Map<String, Object> body = Map.of(
                "traceId", traceId,
                "name", name,
                "value", result.score(),
                "comment", result.reasoning()
        );

        try {
            restClient.post()
                    .uri("/api/public/scores")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[Langfuse] score written: traceId={}, name={}, value={}", traceId, name, result.score());
        } catch (Exception e) {
            log.warn("[Langfuse] failed to write score: {}", e.getMessage());
        }
    }

    /**
     * 批量打分
     */
    public void scoreAll(String traceId, List<JudgeResult> results) {
        for (JudgeResult result : results) {
            score(traceId, result);
        }
    }

    /**
     * 创建或更新 Dataset
     */
    public String ensureDataset(String name, String description) {
        try {
            Map<String, Object> body = Map.of(
                    "name", name,
                    "description", description != null ? description : ""
            );
            String response = restClient.post()
                    .uri("/api/public/datasets")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            log.info("[Langfuse] dataset ensured: name={}", name);
            return response;
        } catch (Exception e) {
            log.warn("[Langfuse] failed to create dataset '{}': {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * 向 Dataset 添加 item
     */
    public String addDatasetItem(String datasetName, EvaluationCase evalCase) {
        try {
            Map<String, Object> body = Map.of(
                    "datasetName", datasetName,
                    "input", Map.of(
                            "id", evalCase.id(),
                            "dimension", evalCase.dimension(),
                            "query", evalCase.query(),
                            "context", evalCase.context() != null ? evalCase.context() : Map.of()
                    ),
                    "expectedOutput", Map.of(
                            "tools", evalCase.expected().tools() != null ? evalCase.expected().tools() : List.of(),
                            "answerCriteria", evalCase.expected().answerCriteria() != null ? evalCase.expected().answerCriteria() : "",
                            "docIds", evalCase.expected().docIds() != null ? evalCase.expected().docIds() : List.of()
                    )
            );
            restClient.post()
                    .uri("/api/public/dataset-items")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[Langfuse] dataset item added: dataset={}, itemId={}", datasetName, evalCase.id());
            return evalCase.id();
        } catch (Exception e) {
            log.warn("[Langfuse] failed to add dataset item '{}': {}", evalCase.id(), e.getMessage());
            return null;
        }
    }

    /**
     * 将 trace 关联到 dataset item，形成 experiment run
     */
    public void linkTraceToDatasetItem(String datasetItemId, String traceId, String runName) {
        try {
            Map<String, Object> body = Map.of(
                    "datasetItemId", datasetItemId,
                    "traceId", traceId,
                    "runName", runName != null ? runName : "evaluation-" + UUID.randomUUID().toString().substring(0, 8)
            );
            restClient.post()
                    .uri("/api/public/dataset-run-items")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[Langfuse] linked trace to dataset: itemId={}, traceId={}", datasetItemId, traceId);
        } catch (Exception e) {
            log.warn("[Langfuse] failed to link trace to dataset item: {}", e.getMessage());
        }
    }
}
