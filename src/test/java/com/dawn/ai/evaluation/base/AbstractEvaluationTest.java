package com.dawn.ai.evaluation.base;

import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import com.dawn.ai.evaluation.judge.JudgeService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 评估测试基类。
 *
 * 前置条件：
 * - docker compose up -d （PostgreSQL + Redis 必须健康）
 * - LLM API 可用（OPENAI_API_KEY / BASE_URL 已配置）
 * - （可选）Langfuse 可用时自动写入评分
 *
 * 运行方式：
 * - mvn test -Dgroups=evaluation
 * - mvn test -Dtest=ToolSelectionEvaluationTest
 */
@SpringBootTest
@Tag("evaluation")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractEvaluationTest.class);
    private static final List<JudgeResult> ALL_RESULTS = Collections.synchronizedList(new ArrayList<>());
    private static final String RUN_ID = "eval-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    protected JudgeService judgeService;

    @Autowired(required = false)
    protected LangfuseScoringClient langfuseClient;

    protected abstract JudgeDimension dimension();

    protected List<EvaluationCase> loadCases() {
        return EvaluationDatasetLoader.loadByDimension(dimension().id());
    }

    protected void sleepBetweenCases() {
        try {
            Thread.sleep(3000); // 3s delay between cases to avoid LLM rate limiting
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected JudgeResult evaluate(EvaluationCase evalCase, Map<String, String> variables) {
        JudgeResult result = judgeService.judge(dimension(), variables);
        ALL_RESULTS.add(result);

        log.info("[Evaluation] case={} | dimension={} | score={} | passed={} | reasoning={}",
                evalCase.id(), dimension().id(), result.score(), result.passed(),
                truncate(result.reasoning(), 100));

        return result;
    }

    protected void writeScoreToLangfuse(String traceId, JudgeResult result) {
        if (langfuseClient != null) {
            try {
                langfuseClient.score(traceId, result);
            } catch (Exception e) {
                log.warn("[Evaluation] failed to write score to Langfuse: {}", e.getMessage());
            }
        }
    }

    @AfterAll
    void printSummary() {
        if (ALL_RESULTS.isEmpty()) return;

        EvaluationReport report = new EvaluationReport();
        ALL_RESULTS.forEach(report::add);

        log.info("\n{}", report.summary());

        // 写入本地 JSON 报告
        writeLocalReport(report);
    }

    private void writeLocalReport(EvaluationReport report) {
        try {
            String path = "target/evaluation-reports/" + RUN_ID + "-" + dimension().id() + ".json";
            java.io.File dir = new java.io.File("target/evaluation-reports");
            dir.mkdirs();

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new java.io.File(path), Map.of(
                    "runId", RUN_ID,
                    "dimension", dimension().id(),
                    "totalCases", report.results().size(),
                    "passRate", report.passRate(),
                    "averageScore", report.averageScore(),
                    "results", report.results().stream().map(r -> Map.of(
                            "dimension", r.dimension().id(),
                            "score", r.score(),
                            "passed", r.passed(),
                            "reasoning", r.reasoning()
                    )).toList()
            ));

            log.info("[Evaluation] report written to {}", path);
        } catch (Exception e) {
            log.warn("[Evaluation] failed to write local report: {}", e.getMessage());
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
