package com.dawn.ai.evaluation.dimensions;

import com.dawn.ai.agent.trace.AgentStep;
import com.dawn.ai.evaluation.base.AbstractEvaluationTest;
import com.dawn.ai.evaluation.base.EvaluationCase;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RagRecallEvaluationTest extends AbstractEvaluationTest {

    @Autowired
    private VectorStore vectorStore;

    @Override
    protected JudgeDimension dimension() {
        return JudgeDimension.RAG_RECALL;
    }

    @Test
    @DisplayName("evaluation: RAG 召回相关性")
    void evaluate_ragRecall() {
        List<EvaluationCase> cases = loadCases();
        assertThat(cases).isNotEmpty();

        for (EvaluationCase evalCase : cases) {
            sleepBetweenCases();
            String sessionId = "eval-rag-" + evalCase.id();

            // 预索引测试文档到向量库
            indexTestDocuments(evalCase);

            StreamedAgentResult result = streamAgent(sessionId, evalCase.query());

            // 提取实际检索到的文档 ID
            List<String> retrievedDocIds = result.steps().stream()
                    .filter(step -> "knowledgeSearchTool".equals(step.toolName()) && step.toolOutput() != null)
                    .flatMap(step -> extractDocIds(step.toolOutput()))
                    .distinct()
                    .toList();

            String retrievedDocsStr = retrievedDocIds.isEmpty() ? "none" : String.join(", ", retrievedDocIds);

            List<String> expectedDocIds = evalCase.expected().docIds();
            String expectedDocIdsStr = (expectedDocIds != null && !expectedDocIds.isEmpty())
                    ? String.join(", ", expectedDocIds)
                    : "none";

            Map<String, String> variables = Map.of(
                    "query", evalCase.query(),
                    "retrieved_documents", retrievedDocsStr,
                    "expected_doc_ids", expectedDocIdsStr
            );

            JudgeResult judgeResult = evaluate(evalCase, variables);
            assertThat(judgeResult.score())
                    .as("RAG recall for case '%s': %s", evalCase.id(), judgeResult.reasoning())
                    .isGreaterThanOrEqualTo(3.0);
        }
    }

    @SuppressWarnings("unchecked")
    private void indexTestDocuments(EvaluationCase evalCase) {
        List<Map<String, Object>> ragDocs = (List<Map<String, Object>>) evalCase.context().get("ragDocuments");
        if (ragDocs == null || ragDocs.isEmpty()) return;

        try {
            List<Document> documents = ragDocs.stream()
                    .map(doc -> {
                        String docId = (String) doc.get("id");
                        String content = (String) doc.get("content");
                        // Spring AI Document requires UUID as ID; use random UUID to avoid conflicts
                        String uuid = UUID.randomUUID().toString();
                        return new Document(uuid, content, Map.of(
                                "source", "evaluation-test",
                                "evalCaseId", evalCase.id(),
                                "originalId", docId
                        ));
                    })
                    .toList();

            vectorStore.add(documents);
        } catch (Exception e) {
            // Log but don't fail - documents may already exist or embedding service may be unavailable
            System.err.println("[Evaluation] Warning: failed to index test documents for " + evalCase.id() + ": " + e.getMessage());
        }
    }

    private java.util.stream.Stream<String> extractDocIds(String output) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("doc-\\S+").matcher(output);
        while (matcher.find()) {
            ids.add(matcher.group());
        }
        return ids.stream();
    }
}
