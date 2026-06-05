package com.dawn.ai.evaluation.base;

import com.dawn.ai.evaluation.judge.JudgeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Langfuse Experiment 对比客户端。
 * 将评估结果以 Experiment（Dataset Run）形式写入 Langfuse，支持跨版本评分趋势对比。
 *
 * 流程：
 * 1. ensureDataset() 创建/获取评估数据集
 * 2. addDatasetItem() 将每条用例写入数据集
 * 3. 运行评估，每条用例产生一个 trace
 * 4. linkTraceToDatasetItem() 将 trace 关联到 dataset item + runName
 * 5. Langfuse Dashboard 的 Dataset 页面可对比不同 runName 的评分
 */
@Component
public class LangfuseExperimentClient {

    private static final Logger log = LoggerFactory.getLogger(LangfuseExperimentClient.class);

    private final LangfuseScoringClient scoringClient;
    private final String datasetName;

    public LangfuseExperimentClient(
            @Autowired(required = false) LangfuseScoringClient scoringClient,
            @Value("${LANGFUSE_EVAL_DATASET:dawn-ai-evaluation}") String datasetName) {
        this.scoringClient = scoringClient;
        this.datasetName = datasetName;
    }

    /**
     * 初始化实验：确保 Dataset 存在，返回 runName
     */
    public String initExperiment(String runName) {
        if (scoringClient == null) {
            log.debug("[Langfuse Experiment] Langfuse not available, skipping");
            return runName;
        }

        if (runName == null) {
            runName = "eval-" + UUID.randomUUID().toString().substring(0, 8);
        }

        scoringClient.ensureDataset(datasetName, "Dawn AI Agent 端到端评估数据集");
        log.info("[Langfuse Experiment] initialized: dataset={}, run={}", datasetName, runName);
        return runName;
    }

    /**
     * 同步用例到 Dataset（幂等，重复添加会被忽略）
     */
    public void syncCases(List<EvaluationCase> cases) {
        if (scoringClient == null) return;

        for (EvaluationCase c : cases) {
            scoringClient.addDatasetItem(datasetName, c);
        }
        log.info("[Langfuse Experiment] synced {} cases to dataset '{}'", cases.size(), datasetName);
    }

    /**
     * 记录单条评估结果：打分 + 关联到 Dataset Run
     */
    public void recordResult(String runName, String caseId, String traceId, JudgeResult result) {
        if (scoringClient == null) return;

        try {
            // 打分
            scoringClient.score(traceId, result);

            // 关联到 dataset run（experiment）
            scoringClient.linkTraceToDatasetItem(caseId, traceId, runName);

            log.info("[Langfuse Experiment] recorded: case={}, run={}, score={}", caseId, runName, result.score());
        } catch (Exception e) {
            log.warn("[Langfuse Experiment] failed to record result for case '{}': {}", caseId, e.getMessage());
        }
    }

    /**
     * 批量记录评估结果
     */
    public void recordAll(String runName, List<ResultEntry> entries) {
        if (scoringClient == null) return;

        for (ResultEntry entry : entries) {
            recordResult(runName, entry.caseId(), entry.traceId(), entry.result());
        }
        log.info("[Langfuse Experiment] recorded {} results for run '{}'", entries.size(), runName);
    }

    public record ResultEntry(String caseId, String traceId, JudgeResult result) {}
}
