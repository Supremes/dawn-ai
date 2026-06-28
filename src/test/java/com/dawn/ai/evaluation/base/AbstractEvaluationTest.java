package com.dawn.ai.evaluation.base;

import com.dawn.ai.agent.orchestration.AgentOrchestrator;
import com.dawn.ai.agent.planning.PlanStep;
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
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
 * - 用 -Deval.shuffle=true 随机抽样；可加 -Deval.seed=123 固定随机种子复现
 */
@SpringBootTest
@ActiveProfiles("evaluation")
@Tag("evaluation")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractEvaluationTest.class);
    private static final List<JudgeResult> ALL_RESULTS = Collections.synchronizedList(new ArrayList<>());
    private static final List<EvaluationCaseResult> ALL_CASE_RESULTS = Collections.synchronizedList(new ArrayList<>());
    private static final String RUN_ID = "eval-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String EVAL_LIMIT_PROPERTY = "eval.limit";
    private static final String EVAL_SHUFFLE_PROPERTY = "eval.shuffle";
    private static final String EVAL_SEED_PROPERTY = "eval.seed";
    private static final int DEFAULT_EVAL_CASE_LIMIT = 5;

    @Autowired
    protected JudgeService judgeService;

    @Autowired
    protected AgentOrchestrator agentOrchestrator;

    @Autowired(required = false)
    protected LangfuseScoringClient langfuseClient;

    protected abstract JudgeDimension dimension();

    protected String evaluationRunId() {
        return RUN_ID;
    }

    protected String evaluationSessionId(String prefix, EvaluationCase evalCase) {
        return "%s-%s-%s".formatted(prefix, evalCase.id(), RUN_ID);
    }

    protected String evaluationTopicId(EvaluationCase evalCase) {
        return "%s-%s".formatted(RUN_ID, evalCase.id());
    }

    protected List<EvaluationCase> loadCases() {
        List<EvaluationCase> cases = EvaluationDatasetLoader.loadByDimension(dimension().id());
        List<EvaluationCase> selectedCases = maybeShuffle(cases);
        int limit = resolveCaseLimit(cases.size());
        if (limit >= selectedCases.size()) {
            log.info("[Evaluation] dimension={} | selected all {} case(s)", dimension().id(), cases.size());
            return selectedCases;
        }

        log.info("[Evaluation] dimension={} | selected {}/{} case(s), override with -D{}=<count>",
                dimension().id(), limit, cases.size(), EVAL_LIMIT_PROPERTY);
        return selectedCases.stream().limit(limit).toList();
    }

    private List<EvaluationCase> maybeShuffle(List<EvaluationCase> cases) {
        if (!Boolean.parseBoolean(System.getProperty(EVAL_SHUFFLE_PROPERTY, "false"))) {
            return cases;
        }

        long seed = resolveShuffleSeed();
        List<EvaluationCase> shuffled = new ArrayList<>(cases);
        Collections.shuffle(shuffled, new Random(seed));
        log.info("[Evaluation] dimension={} | shuffled {} case(s) with seed={}, override with -D{}=<seed>",
                dimension().id(), shuffled.size(), seed, EVAL_SEED_PROPERTY);
        return shuffled;
    }

    private long resolveShuffleSeed() {
        String rawSeed = System.getProperty(EVAL_SEED_PROPERTY);
        if (rawSeed == null || rawSeed.isBlank()) {
            return System.nanoTime();
        }

        try {
            return Long.parseLong(rawSeed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "System property eval.seed must be a long integer, but was: " + rawSeed, e);
        }
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

    protected void recordCaseResult(EvaluationCaseResult caseResult) {
        ALL_RESULTS.add(caseResult.result());
        ALL_CASE_RESULTS.add(caseResult);

        log.info("[Evaluation] case={} | dimension={} | score={} | passed={} | reasoning={}",
                caseResult.evaluationCase().id(), dimension().id(), caseResult.result().score(),
                caseResult.result().passed(), truncate(caseResult.result().reasoning(), 100));
    }

    protected StreamedAgentResult streamAgent(String sessionId, String query) {
        return streamAgent(sessionId, query, null);
    }

    protected StreamedAgentResult streamAgent(String sessionId, String query, String topicId) {
        List<ChatStreamEvent> events = new ArrayList<>();
        agentOrchestrator.streamChat(sessionId, query, topicId, events::add, () -> false);

        List<PlanStep> plan = events.stream()
                .filter(event -> "plan".equals(event.getEvent()))
                .findFirst()
                .map(this::toPlanSteps)
                .orElse(List.of());

        return events.stream()
                .filter(event -> "done".equals(event.getEvent()))
                .map(event -> toStreamedResult(event.getData(), plan))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Agent stream did not produce done event"));
    }

    @SuppressWarnings("unchecked")
    protected List<String> indexEvaluationDocuments(VectorStore vectorStore, EvaluationCase evalCase) {
        List<Map<String, Object>> ragDocs = (List<Map<String, Object>>) evalCase.context().get("ragDocuments");
        if (ragDocs == null || ragDocs.isEmpty()) {
            return List.of();
        }

        List<Document> documents = ragDocs.stream()
                .map(doc -> {
                    String documentId = UUID.randomUUID().toString();
                    String originalId = stringValue(doc.get("id"), documentId);
                    String content = stringValue(doc.get("content"), "");
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("source", "evaluation-test");
                    metadata.put("evalRunId", RUN_ID);
                    metadata.put("evalCaseId", evalCase.id());
                    metadata.put("originalId", originalId);
                    metadata.put("category", categoryOf(doc));
                    metadata.put("topicId", evaluationTopicId(evalCase));
                    return new Document(documentId, content, metadata);
                })
                .toList();

        vectorStore.add(documents);
        return documents.stream().map(Document::getId).toList();
    }

    protected void deleteEvaluationDocuments(VectorStore vectorStore, List<String> documentIds) {
        if (documentIds != null && !documentIds.isEmpty()) {
            vectorStore.delete(documentIds);
        }
    }

    protected Map<String, List<String>> evaluationMetadataFilters(EvaluationCase evalCase) {
        Map<String, List<String>> filters = new HashMap<>();
        filters.put("source", List.of("evaluation-test"));
        filters.put("evalRunId", List.of(RUN_ID));
        filters.put("evalCaseId", List.of(evalCase.id()));
        filters.put("category", evaluationCategories(evalCase));
        return filters;
    }

    private String categoryOf(Map<String, Object> doc) {
        Object category = doc.get("category");
        if (category instanceof String value && !value.isBlank()) {
            return value;
        }
        return "general";
    }

    @SuppressWarnings("unchecked")
    private List<String> evaluationCategories(EvaluationCase evalCase) {
        List<Map<String, Object>> ragDocs = (List<Map<String, Object>>) evalCase.context().get("ragDocuments");
        if (ragDocs == null || ragDocs.isEmpty()) {
            return List.of("general");
        }
        List<String> categories = ragDocs.stream()
                .map(this::categoryOf)
                .distinct()
                .toList();
        return categories.isEmpty() ? List.of("general") : categories;
    }

    private String stringValue(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    @SuppressWarnings("unchecked")
    private StreamedAgentResult toStreamedResult(Object data, List<PlanStep> plan) {
        Map<String, Object> payload = data instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        Object answer = payload.get("answer");
        Object steps = payload.get("steps");
        return new StreamedAgentResult(
                answer instanceof String text ? text : null,
                steps instanceof List<?> list ? (List<AgentStep>) list : List.of(),
                plan
        );
    }

    @SuppressWarnings("unchecked")
    private List<PlanStep> toPlanSteps(ChatStreamEvent event) {
        Map<String, Object> payload = event.getData() instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        Object steps = payload.get("steps");
        if (!(steps instanceof List<?> list)) {
            return List.of();
        }

        List<PlanStep> parsed = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof PlanStep step) {
                parsed.add(step);
            } else if (item instanceof Map<?, ?> stepMap) {
                Object stepNumber = stepMap.get("step");
                Object action = stepMap.get("action");
                Object reason = stepMap.get("reason");
                if (stepNumber instanceof Number number && action instanceof String actionText
                        && reason instanceof String reasonText) {
                    parsed.add(new PlanStep(number.intValue(), actionText, reasonText));
                }
            }
        }
        return parsed;
    }

    protected record StreamedAgentResult(String finalAnswer, List<AgentStep> steps, List<PlanStep> plannerSteps) {}

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
        ALL_CASE_RESULTS.forEach(report::addCaseResult);

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
                    "results", report.caseResults().isEmpty()
                            ? report.results().stream().map(this::resultToMap).toList()
                            : report.caseResults().stream().map(this::caseResultToMap).toList()
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

    private Map<String, Object> resultToMap(JudgeResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dimension", result.dimension().id());
        map.put("score", result.score());
        map.put("passed", result.passed());
        map.put("reasoning", result.reasoning());
        return map;
    }

    private Map<String, Object> caseResultToMap(EvaluationCaseResult caseResult) {
        Map<String, Object> map = resultToMap(caseResult.result());
        map.put("caseId", caseResult.evaluationCase().id());
        map.put("query", caseResult.evaluationCase().query());
        map.put("sessionId", caseResult.sessionId());
        map.put("expectedTools", caseResult.expectedTools());
        map.put("actualTools", caseResult.actualTools());
        map.put("forbiddenTools", caseResult.forbiddenTools());
        map.put("allowExtraTools", caseResult.allowExtraTools());
        map.put("finalAnswer", caseResult.finalAnswer());
        map.put("plannerSteps", caseResult.plannerSteps().stream().map(step -> {
            Map<String, Object> stepMap = new LinkedHashMap<>();
            stepMap.put("step", step.step());
            stepMap.put("action", step.action());
            stepMap.put("reason", step.reason());
            return stepMap;
        }).toList());
        map.put("agentSteps", caseResult.agentSteps().stream().map(step -> {
            Map<String, Object> stepMap = new LinkedHashMap<>();
            stepMap.put("stepNumber", step.stepNumber());
            stepMap.put("toolName", step.toolName());
            stepMap.put("toolInput", String.valueOf(step.toolInput()));
            stepMap.put("toolOutput", step.toolOutput());
            stepMap.put("durationMs", step.durationMs());
            stepMap.put("status", step.status());
            return stepMap;
        }).toList());
        return map;
    }
}
