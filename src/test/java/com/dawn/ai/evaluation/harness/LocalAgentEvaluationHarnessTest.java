package com.dawn.ai.evaluation.harness;

import com.dawn.ai.agent.tools.BashTool;
import com.dawn.ai.agent.trace.StepCollector;
import com.dawn.ai.evaluation.retrieval.RetrievalEvaluationCase;
import com.dawn.ai.evaluation.retrieval.RetrievalEvaluationReport;
import com.dawn.ai.evaluation.retrieval.RetrievalEvaluator;
import com.dawn.ai.rag.retrieval.RetrievalRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class LocalAgentEvaluationHarnessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("local harness: mock AI + SQLite + memory + local report 覆盖核心链路")
    void localHarness_validatesCoreAgentLinks() throws Exception {
        List<RetrievalEvaluationCase> cases = loadRetrievalCases();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(300);

        try (SqliteHarnessStore store = SqliteHarnessStore.open(tempDir.resolve("harness.db"))) {
            store.load(cases);

            RetrievalEvaluationReport ragReport = new RetrievalEvaluator().evaluate(cases, store::retrieve, 3);
            assertThat(ragReport.caseCount()).isEqualTo(cases.size());
            assertThat(ragReport.recallAtK()).isGreaterThanOrEqualTo(0.70);
            assertThat(ragReport.hitRateAtK()).isGreaterThanOrEqualTo(0.70);
            assertThat(ragReport.mrrAtK()).isGreaterThanOrEqualTo(0.50);
            assertThat(ragReport.ndcgAtK()).isGreaterThanOrEqualTo(0.50);
            assertThat(ragReport.precisionAtK()).isGreaterThan(0.0);
            assertThat(ragReport.noiseRateAtK()).isLessThan(1.0);

            BashTool bashTool = readonlyBashTool();
            StepCollector.init(3);
            try {
                BashTool.Response blocked = bashTool.apply(new BashTool.Request("touch blocked.txt"));
                assertThat(blocked.exitCode()).isEqualTo(-1);
                assertThat(blocked.error()).contains("只读安全模式");

                BashTool.Response safeRead = bashTool.apply(new BashTool.Request("printf harness-ok"));
                assertThat(safeRead.exitCode()).isEqualTo(0);
                assertThat(safeRead.stdout()).isEqualTo("harness-ok");
            } finally {
                StepCollector.clear();
            }

            InMemoryMemoryStore memory = new InMemoryMemoryStore();
            LocalAgentApi api = new LocalAgentApi(new MockAiRouter(), new ToolQueue(3), memory, store);

            LocalApiResponse webAndBash = api.handle("session-1", "搜索最新 AI 新闻，然后查看服务器 /tmp 目录");
            assertThat(webAndBash.status()).isEqualTo(200);
            assertThat(webAndBash.route()).isEqualTo("tool_queue");
            assertThat(webAndBash.tools()).containsExactly("webTool", "bashTool");
            assertThat(webAndBash.answer()).contains("AI_news").contains("/tmp_listing");

            LocalApiResponse knowledge = api.handle("session-1", "退款政策");
            assertThat(knowledge.status()).isEqualTo(200);
            assertThat(knowledge.tools()).containsExactly("knowledgeSearchTool");
            assertThat(knowledge.retrievedDocIds()).first().isEqualTo("doc-billing-refund-policy");
            assertThat(memory.history("session-1")).hasSize(4);
            assertThat(store.apiEventCount("session-1")).isEqualTo(2);

            assertThatThrownBy(() -> {
                ToolQueue queue = new ToolQueue(2);
                queue.enqueueAll(List.of("webTool", "bashTool", "knowledgeSearchTool"));
                queue.execute(tool -> tool);
            }).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("max tool queue size");

            Path reportPath = writeLocalReport(ragReport, webAndBash, knowledge);
            assertThat(reportPath).exists();
            assertThat(Files.readString(reportPath)).contains("\"caseCount\"");
        }
    }

    private List<RetrievalEvaluationCase> loadRetrievalCases() throws Exception {
        try (InputStream inputStream = getClass().getResourceAsStream("/evaluation/retrieval-eval-dataset.json")) {
            return MAPPER.readValue(inputStream, new TypeReference<>() {});
        }
    }

    private BashTool readonlyBashTool() {
        BashTool bashTool = new BashTool();
        ReflectionTestUtils.setField(bashTool, "baseDir", tempDir.toString());
        ReflectionTestUtils.setField(bashTool, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(bashTool, "maxOutputBytes", 4096);
        ReflectionTestUtils.setField(bashTool, "maxConsecutiveFailures", 3);
        ReflectionTestUtils.setField(bashTool, "allowWrite", false);
        return bashTool;
    }

    private Path writeLocalReport(
            RetrievalEvaluationReport ragReport,
            LocalApiResponse weatherAndCalc,
            LocalApiResponse knowledge) throws Exception {
        Path dir = Path.of("target/evaluation-reports");
        Files.createDirectories(dir);
        Path reportPath = dir.resolve("local-agent-harness-report.json");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("harness", "local-agent");
        report.put("storage", "temporary-sqlite");
        report.put("memory", "in-memory");
        report.put("rag", Map.of(
                "caseCount", ragReport.caseCount(),
                "recallAtK", ragReport.recallAtK(),
                "precisionAtK", ragReport.precisionAtK(),
                "noiseRateAtK", ragReport.noiseRateAtK(),
                "hitRateAtK", ragReport.hitRateAtK(),
                "mrrAtK", ragReport.mrrAtK(),
                "ndcgAtK", ragReport.ndcgAtK()
        ));
        report.put("apiResponses", List.of(weatherAndCalc, knowledge));
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
        return reportPath;
    }

    private record LocalApiResponse(
            int status,
            String route,
            String answer,
            List<String> tools,
            List<String> retrievedDocIds
    ) {}

    private static final class MockAiRouter {
        List<String> route(String query) {
            String normalized = query.toLowerCase();
            boolean web = normalized.contains("搜索") || normalized.contains("新闻") || normalized.contains("search") || normalized.contains("latest");
            boolean bash = normalized.contains("服务器") || normalized.contains("查看") || normalized.contains("命令") || normalized.contains("server");
            if (web && bash) {
                return List.of("webTool", "bashTool");
            }
            if (web) {
                return List.of("webTool");
            }
            if (bash) {
                return List.of("bashTool");
            }
            return List.of("knowledgeSearchTool");
        }
    }

    private static final class ToolQueue {
        private final int maxSize;
        private final ArrayDeque<String> queue = new ArrayDeque<>();

        ToolQueue(int maxSize) {
            this.maxSize = maxSize;
        }

        void enqueueAll(List<String> tools) {
            if (queue.size() + tools.size() > maxSize) {
                throw new IllegalStateException("max tool queue size exceeded: " + maxSize);
            }
            queue.addAll(tools);
        }

        List<String> execute(Function<String, String> executor) {
            List<String> outputs = new ArrayList<>();
            while (!queue.isEmpty()) {
                outputs.add(executor.apply(queue.removeFirst()));
            }
            return outputs;
        }
    }

    private static final class InMemoryMemoryStore {
        private final Map<String, List<Map<String, String>>> messages = new ConcurrentHashMap<>();

        void add(String sessionId, String role, String content) {
            messages.computeIfAbsent(sessionId, ignored -> new ArrayList<>())
                    .add(Map.of("role", role, "content", content));
        }

        List<Map<String, String>> history(String sessionId) {
            return List.copyOf(messages.getOrDefault(sessionId, List.of()));
        }
    }

    private static final class LocalAgentApi {
        private final MockAiRouter router;
        private final ToolQueue queue;
        private final InMemoryMemoryStore memory;
        private final SqliteHarnessStore store;

        LocalAgentApi(MockAiRouter router, ToolQueue queue, InMemoryMemoryStore memory, SqliteHarnessStore store) {
            this.router = router;
            this.queue = queue;
            this.memory = memory;
            this.store = store;
        }

        LocalApiResponse handle(String sessionId, String query) throws Exception {
            memory.add(sessionId, "user", query);
            List<String> tools = router.route(query);
            queue.enqueueAll(tools);

            List<Document> retrieved = new ArrayList<>();
            List<String> outputs = queue.execute(tool -> switch (tool) {
                case "webTool" -> "result=AI_news";
                case "bashTool" -> "stdout=/tmp_listing";
                case "knowledgeSearchTool" -> {
                    retrieved.addAll(store.retrieve(RetrievalRequest.builder().query(query).topK(3).build()));
                    yield "docs=" + retrieved.stream().map(Document::getId).toList();
                }
                default -> throw new IllegalArgumentException("Unknown tool: " + tool);
            });

            String route = tools.size() > 1 ? "tool_queue" : tools.get(0);
            String answer = String.join("; ", outputs);
            memory.add(sessionId, "assistant", answer);
            store.recordApiEvent(sessionId, query, route, answer);
            return new LocalApiResponse(
                    200,
                    route,
                    answer,
                    tools,
                    retrieved.stream().map(Document::getId).toList());
        }
    }

    private static final class SqliteHarnessStore implements AutoCloseable {
        private final Connection connection;

        private SqliteHarnessStore(Connection connection) {
            this.connection = connection;
        }

        static SqliteHarnessStore open(Path path) throws Exception {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            SqliteHarnessStore store = new SqliteHarnessStore(connection);
            store.initSchema();
            return store;
        }

        void initSchema() throws Exception {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("create table documents (id text primary key, content text not null, category text not null)");
                statement.executeUpdate("create table rankings (query text not null, rank integer not null, doc_id text not null)");
                statement.executeUpdate("create table api_events (session_id text not null, query text not null, route text not null, answer text not null)");
            }
        }

        void load(List<RetrievalEvaluationCase> cases) throws Exception {
            for (RetrievalEvaluationCase evaluationCase : cases) {
                String category = evaluationCase.metadataFilters()
                        .getOrDefault("category", List.of("general"))
                        .get(0);
                List<String> docIds = Stream.of(
                                evaluationCase.expectedDocIds(),
                                evaluationCase.hardNegativeDocIds(),
                                evaluationCase.mockRankedDocIds())
                        .flatMap(List::stream)
                        .distinct()
                        .toList();
                for (String docId : docIds) {
                    upsertDocument(docId, "Mock document for " + docId, category);
                }
                for (int index = 0; index < evaluationCase.mockRankedDocIds().size(); index++) {
                    insertRanking(evaluationCase.query(), index + 1, evaluationCase.mockRankedDocIds().get(index));
                }
            }
        }

        List<Document> retrieve(RetrievalRequest request) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select d.id, d.content
                    from rankings r
                    join documents d on d.id = r.doc_id
                    where r.query = ?
                    order by r.rank asc
                    limit ?
                    """)) {
                statement.setString(1, request.getQuery());
                statement.setInt(2, request.getTopK());
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<Document> documents = new ArrayList<>();
                    while (resultSet.next()) {
                        documents.add(new Document(
                                resultSet.getString("id"),
                                resultSet.getString("content"),
                                Map.of("source", "local-harness")));
                    }
                    return documents;
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to retrieve local harness documents", e);
            }
        }

        void recordApiEvent(String sessionId, String query, String route, String answer) throws Exception {
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into api_events(session_id, query, route, answer) values (?, ?, ?, ?)")) {
                statement.setString(1, sessionId);
                statement.setString(2, query);
                statement.setString(3, route);
                statement.setString(4, answer);
                statement.executeUpdate();
            }
        }

        int apiEventCount(String sessionId) throws Exception {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select count(*) from api_events where session_id = ?")) {
                statement.setString(1, sessionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getInt(1) : 0;
                }
            }
        }

        private void upsertDocument(String id, String content, String category) throws Exception {
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert or ignore into documents(id, content, category) values (?, ?, ?)")) {
                statement.setString(1, id);
                statement.setString(2, content);
                statement.setString(3, category);
                statement.executeUpdate();
            }
        }

        private void insertRanking(String query, int rank, String docId) throws Exception {
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into rankings(query, rank, doc_id) values (?, ?, ?)")) {
                statement.setString(1, query);
                statement.setInt(2, rank);
                statement.setString(3, docId);
                statement.executeUpdate();
            }
        }

        @Override
        public void close() throws Exception {
            connection.close();
        }
    }
}
