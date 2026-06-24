package com.dawn.ai.evaluation.base;

import com.dawn.ai.agent.orchestration.AgentOrchestrator;
import com.dawn.ai.agent.trace.AgentStep;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import com.dawn.ai.evaluation.judge.JudgeService;
import com.dawn.ai.sse.ChatStreamEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

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
 * - LLM API 可用（application-evaluation.yml 会自动导入项目根目录 .env）
 * - （可选）Langfuse 可用时自动写入评分
 *
 * 运行方式：
 * - mvn test -Dgroups=evaluation -Dexcluded.test.groups=
 * - mvn test -Dtest=ToolSelectionEvaluationTest -Dexcluded.test.groups=
 * - 默认每个维度只跑前 5 条；用 -Deval.limit=100 显式扩大数量
 */
@SpringBootTest
@ActiveProfiles("evaluation")
@Tag("evaluation")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractEvaluationTest.class);
    private static final List<JudgeResult> ALL_RESULTS = Collections.synchronizedList(new ArrayList<>());
    private static final String RUN_ID = "eval-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String EVAL_LIMIT_PROPERTY = "eval.limit";
    private static final int DEFAULT_EVAL_CASE_LIMIT = 5;

    @Autowired
    protected JudgeService judgeService;

    @Autowired
    protected AgentOrchestrator agentOrchestrator;

    @Autowired(required = false)
    protected LangfuseScoringClient langfuseClient;

    protected abstract JudgeDimension dimension();

    protected List<EvaluationCase> loadCases() {
        List<EvaluationCase> cases = EvaluationDatasetLoader.loadByDimension(dimension().id());
        int limit = resolveCaseLimit(cases.size());
        if (limit >= cases.size()) {
            log.info("[Evaluation] dimension={} | selected all {} case(s)", dimension().id(), cases.size());
            return cases;
        }

        log.info("[Evaluation] dimension={} | selected {}/{} case(s), override with -D{}=<count>",
                dimension().id(), limit, cases.size(), EVAL_LIMIT_PROPERTY);
        return cases.stream().limit(limit).toList();
    }

    private int resolveCaseLimit(int totalCases) {
        String rawLimit = System.getProperty(EVAL_LIMIT_PROPERTY);
        if (rawLimit == null || rawLimit.isBlank()) {
            return Math.min(DEFAULT_EVAL_CASE_LIMIT, totalCases);
        }

        try {
            int limit = Integer.parseInt(rawLimit);
            if (limit <= 0) {
                throw new IllegalArgumentException(
                        "System property eval.limit must be a positive integer, but was: " + rawLimit);
            }
            return limit;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "System property eval.limit must be a positive integer, but was: " + rawLimit, e);
        }
    }

    protected void sleepBetweenCases() {
        try {
            Thread.sleep(10000); // 10s delay between cases to avoid LLM 429 rate limiting
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected JudgeResult evaluate(EvaluationCase evalCase, Map<String, String> variables) {
        JudgeResult result = judgeService.judge(dimension(), variables);
        recordResult(evalCase, result);
        return result;
    }

    protected void recordResult(EvaluationCase evalCase, JudgeResult result) {
        ALL_RESULTS.add(result);

        log.info("[Evaluation] case={} | dimension={} | score={} | passed={} | reasoning={}",
                evalCase.id(), dimension().id(), result.score(), result.passed(),
                truncate(result.reasoning(), 100));
    }

    protected StreamedAgentResult streamAgent(String sessionId, String query) {
        List<ChatStreamEvent> events = new ArrayList<>();
        agentOrchestrator.streamChat(sessionId, query, null, events::add, () -> false);

        return events.stream()
                .filter(event -> "done".equals(event.getEvent()))
                .map(event -> toStreamedResult(event.getData()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Agent stream did not produce done event"));
    }

    @SuppressWarnings("unchecked")
    private StreamedAgentResult toStreamedResult(Object data) {
        Map<String, Object> payload = data instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        Object answer = payload.get("answer");
        Object steps = payload.get("steps");
        return new StreamedAgentResult(
                answer instanceof String text ? text : null,
                steps instanceof List<?> list ? (List<AgentStep>) list : List.of()
        );
    }

    protected record StreamedAgentResult(String finalAnswer, List<AgentStep> steps) {}

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

            // Generate HTML report alongside the JSON report
            com.dawn.ai.evaluation.report.HtmlReportWriter.write(
                    report, RUN_ID, dimension().id(), java.nio.file.Path.of("target/evaluation-reports"));
            log.info("[Evaluation] HTML report written to target/evaluation-reports/{}-{}.html", RUN_ID, dimension().id());
        } catch (Exception e) {
            log.warn("[Evaluation] failed to write local report: {}", e.getMessage());
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
