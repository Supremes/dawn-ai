package com.dawn.ai.memory;

import com.dawn.ai.service.MemoryService;
import org.awaitility.Durations;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Memory 管道 E2E 集成测试（真实 Redis + PGVector）
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  前置条件                                                            │
 * │  docker compose up -d   （Redis:6379 + PostgreSQL/PGVector:5432）   │
 * │  确认：docker ps | grep -E "redis|postgres"  均为 healthy            │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * 测试策略：
 *   ✅ 真实外部 DB   — Redis（对话历史 + UserProfile）
 *                   — PostgreSQL/PGVector（摘要 + Reflection 向量）
 *   🔧 Mock AI 服务 — ChatModel（LLM，非 DB 依赖）返回固定摘要文本
 *                   — EmbeddingModel（嵌入服务，非 DB 依赖）返回固定 4 维向量
 *
 * 管道层级与触发阈值（来自 application-e2e-test.yml）：
 *   L1  Redis 滑动窗口    MAX_HISTORY = 20 条
 *   L2  Pending 摘要化    batch-size  = 3  条
 *   L3  VectorStore 情节  reflection-threshold = 3 次
 *   L4  UserProfile 画像  episode-threshold    = 4 条
 *
 * 消息数与触发规律：
 *   发送 23 条 → pending 满 3 次 → 1 次摘要 + 1 次 consolidation
 *   发送 26 条 → 2 次摘要 + 2 次 consolidation
 *   发送 29 条 → 3 次摘要 + 3 次 consolidation → ReflectionEvent → L4 写入
 *
 * 测试隔离：
 *   每个测试使用 UUID 后缀的 sessionId，@AfterEach 清理 Redis key。
 *   VectorStore 文档通过 sessionId filter 查询，不影响其他测试。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // 环境变量占位，让 Spring 能解析 ${OPENAI_API_KEY} 等 placeholder
                "OPENAI_API_KEY=e2e-test-key-not-real",
                "BASE_URL=http://localhost:1",
                "CHAT_MODEL=e2e-test-model",
                "EMBEDDING_MODEL=e2e-test-embed",
                "EMBEDDING_BASE_URL=http://localhost:1",
                "EMBEDDING_API_KEY=e2e-test-key-not-real",
                "EMBEDDING_DIMENSIONS=4"
        }
)
@ActiveProfiles("e2e-test")
@DisplayName("Memory Pipeline E2E — 真实 Redis + PGVector")
@TestMethodOrder(MethodOrderer.DisplayName.class)
class MemoryPipelineE2ETest {

    // ─────────────────────────────────────────────────────────────────
    //  固定 Mock 向量（4 维，与 application-e2e-test.yml dimensions=4 对齐）
    // ─────────────────────────────────────────────────────────────────
    private static final float[] MOCK_VECTOR = {0.1f, 0.2f, 0.3f, 0.4f};

    // ─────────────────────────────────────────────────────────────────
    //  AI 服务 Mock（LLM + Embedding 不是 DB，仅 mock 推理结果）
    // ─────────────────────────────────────────────────────────────────
    @MockBean
    EmbeddingModel embeddingModel;

    @MockBean
    ChatModel chatModel;

    // ─────────────────────────────────────────────────────────────────
    //  真实 Spring Bean（连接到本地 Docker 服务）
    // ─────────────────────────────────────────────────────────────────
    @Autowired
    MemoryService memoryService;

    @Autowired
    VectorStore vectorStore;

    @Autowired
    RedisTemplate<String, Object> redisTemplate;

    @Autowired
    UserProfileService userProfileService;

    @Autowired
    EvictionPolicyManager evictionPolicyManager;

    // ─────────────────────────────────────────────────────────────────
    //  测试状态（每个测试独立 sessionId，AfterEach 统一清理）
    // ─────────────────────────────────────────────────────────────────
    private final List<String> usedSessionIds = new ArrayList<>();

    @BeforeEach
    void setupAiMocks() {
        // EmbeddingModel：所有文本统一映射到固定 4 维向量
        // PGVector 存储 / 检索均依赖此向量，cosine(v, v) = 1.0，filter 正常工作
        EmbeddingResponse er = new EmbeddingResponse(
                List.of(new Embedding(MOCK_VECTOR, 0)));
        when(embeddingModel.call(any(EmbeddingRequest.class))).thenReturn(er);

        // PgVectorStore 1.1.x 调用 embed(List<Document>, EmbeddingOptions, BatchingStrategy)（default 方法）
        // Mockito 对 default interface 方法默认返回空 List，必须显式 stub
        doAnswer(inv -> {
            List<?> docs = inv.getArgument(0);
            return Collections.nCopies(docs.size(), MOCK_VECTOR);
        }).when(embeddingModel).embed(anyList(), any(EmbeddingOptions.class), any(BatchingStrategy.class));

        // PgVectorStore 查询时调用 embed(String)（default 方法）
        doReturn(MOCK_VECTOR).when(embeddingModel).embed(anyString());

        // ChatModel：返回可预测的摘要文本，用于验证 L2/L4 写入内容
        Generation gen = new Generation(
                new AssistantMessage("E2E摘要：用户在深入讨论 Go 并发模式与微服务架构设计。"));
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(gen)));
    }

    @AfterEach
    void cleanupRedis() {
        for (String sid : usedSessionIds) {
            memoryService.clearSession(sid);
            // UserProfile 存在独立 key，clearSession 不会清除，需要单独删除
            redisTemplate.delete("ai:profile:" + sid);
        }
        usedSessionIds.clear();
    }

    // ═════════════════════════════════════════════════════════════════
    //  L1 — Redis 滑动窗口（真实 Redis 读写验证）
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("e2e-01 · L1 · 消息写入真实 Redis，读取顺序与内容完全正确")
    void e2e01_l1_messages_persisted_and_ordered_in_redis() {
        String sid = sid("l1-order");

        memoryService.addMessage(sid, "user",      "你好，我是 Alice");
        memoryService.addMessage(sid, "assistant", "你好 Alice！有什么可以帮你的？");
        memoryService.addMessage(sid, "user",      "我想学 Go 语言的并发模型");
        memoryService.addMessage(sid, "assistant", "建议从 goroutine 和 channel 入手。");
        memoryService.addMessage(sid, "user",      "好的，先从 channel 开始");

        List<Map<String, String>> history = memoryService.getHistory(sid);

        assertThat(history).hasSize(5);
        assertThat(history.get(0))
                .containsEntry("role", "user")
                .containsEntry("content", "你好，我是 Alice");
        assertThat(history.get(2))
                .containsEntry("role", "user")
                .containsEntry("content", "我想学 Go 语言的并发模型");
        assertThat(history.get(4))
                .containsEntry("role", "user")
                .containsEntry("content", "好的，先从 channel 开始");
    }

    @Test
    @DisplayName("e2e-02 · L1 · clearSession 后 Redis key 被真实删除，getHistory 返回空")
    void e2e02_l1_clearSession_removes_redis_key() {
        String sid = sid("l1-clear");

        memoryService.addMessage(sid, "user",      "待删消息 A");
        memoryService.addMessage(sid, "assistant", "待删消息 B");
        assertThat(memoryService.getHistory(sid)).hasSize(2);

        memoryService.clearSession(sid);

        assertThat(memoryService.getHistory(sid)).isEmpty();
    }

    @Test
    @DisplayName("e2e-03 · L1 · 消息超过 MAX_HISTORY(20) 时，活跃窗口保持 ≤20 条，最旧消息被滚出")
    void e2e03_l1_sliding_window_caps_at_max_history() {
        String sid = sid("l1-cap");

        // 发 25 条消息：前 20 条填满窗口，第 21-25 条每条溢出 1 条到 pending
        for (int i = 1; i <= 25; i++) {
            memoryService.addMessage(sid, "user", "消息 " + i);
        }

        List<Map<String, String>> history = memoryService.getHistory(sid);

        assertThat(history).hasSizeLessThanOrEqualTo(20);

        // 最新消息仍在窗口
        boolean hasLatest = history.stream()
                .anyMatch(m -> "消息 25".equals(m.get("content")));
        assertThat(hasLatest).isTrue();

        // 最早消息已被挤出
        boolean hasOldest = history.stream()
                .anyMatch(m -> "消息 1".equals(m.get("content")));
        assertThat(hasOldest).isFalse();
    }

    // ═════════════════════════════════════════════════════════════════
    //  L2 — 摘要化（async，等待 VectorStore 真实写入）
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("e2e-04 · L1→L2→L3 · 23 条消息触发摘要，摘要被真实写入 PGVector")
    void e2e04_overflow_triggers_summary_written_to_pgvector() {
        String sid = sid("l2-summary");

        // 23 条：前 20 条填满活跃窗口，第 21/22/23 条各溢出 1 条到 pending
        // pending 满 3（= batch-size）→ SummarizationEvent → MemorySummarizer（@Async）
        //   → ConsolidationEvent → MemoryConsolidator（@Async）→ VectorStore.add()
        sendMessages(sid, 23);

        List<Document> docs = awaitVectorDocs(sid, 1, Duration.ofSeconds(15));

        assertThat(docs).hasSize(1);
        Map<String, Object> meta = docs.get(0).getMetadata();
        assertThat(meta)
                .containsEntry("type", "summary")
                .containsKey("createdAt");
        assertThat(meta.get("sessionId")).isEqualTo(sid);
        assertThat(((Number) meta.get("importance")).doubleValue()).isEqualTo(0.5);
        assertThat(docs.get(0).getText()).contains("E2E摘要");
    }

    @Test
    @DisplayName("e2e-05 · L2 · PGVector 摘要可通过相似搜索检索（真实向量存取往返）")
    void e2e05_summary_is_searchable_via_similarity_search() {
        String sid = sid("l2-search");

        sendMessages(sid, 23);
        awaitVectorDocs(sid, 1, Duration.ofSeconds(15));

        // 用不同查询词验证相似搜索往返（mock embedding 全返回相同向量，余弦相似度 = 1.0）
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("Go 并发架构设计")
                        .topK(5)
                        .similarityThreshold(0.0)
                        .filterExpression(
                                new FilterExpressionBuilder().eq("sessionId", sid).build())
                        .build());

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).getText()).isNotBlank();
        assertThat(results.get(0).getMetadata()).containsEntry("type", "summary");
    }

    @Test
    @DisplayName("e2e-06 · L2 · LLM 返回 Fallback 时（低 importance=0.3），文档仍写入 PGVector")
    void e2e06_llm_failure_fallback_still_stored_with_low_importance() {
        String sid = sid("l2-fallback");

        // 让 ChatModel 抛异常，MemorySummarizer 会降级写入原始文本（importance=0.3）
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("Simulated LLM timeout"));

        sendMessages(sid, 23);

        List<Document> docs = awaitVectorDocs(sid, 1, Duration.ofSeconds(15));

        assertThat(docs).hasSize(1);
        double importance = ((Number) docs.get(0).getMetadata().get("importance")).doubleValue();
        assertThat(importance)
                .as("LLM Fallback 路径 importance 应 < 0.4")
                .isLessThan(0.4);
    }

    // ═════════════════════════════════════════════════════════════════
    //  L3 — 情节记忆 & Reflection 触发
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("e2e-07 · L3→L4 · 29 条消息触发 3 次 consolidation，进而触发 Reflection")
    void e2e07_three_consolidations_trigger_reflection_and_user_profile() {
        String sid = sid("l3-reflection");

        // 29 条消息触发过程：
        //   msg 21-23 → pending 满 → 摘要1 → consolidation1（count=1）
        //   msg 24-26 → pending 满 → 摘要2 → consolidation2（count=2）
        //   msg 27-29 → pending 满 → 摘要3 → consolidation3（count=3 = threshold）
        //             → ReflectionRequestEvent → ReflectionWorker 查 VectorStore
        //             → LLM 提炼画像 → VectorStore.add(reflection) + UserProfile.upsert
        sendMessages(sid, 29);

        // ── L4 验证：UserProfile 真实写入 Redis Hash ─────────────────
        await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    Map<String, String> profile = userProfileService.getProfile(sid);
                    assertThat(profile)
                            .as("UserProfile 应包含 reflection 字段（L4 写入）")
                            .containsKey("reflection");
                    assertThat(profile.get("reflection")).isNotBlank();
                });

        // ── L3 验证：VectorStore 有 reflection 类型文档 ──────────────
        List<Document> allDocs = awaitVectorDocs(sid, 3, Duration.ofSeconds(5));
        boolean hasReflection = allDocs.stream()
                .anyMatch(d -> "reflection".equals(d.getMetadata().get("type")));
        assertThat(hasReflection)
                .as("VectorStore 中应存在 type=reflection 的文档")
                .isTrue();

        // ── 验证 reflection 文档 importance ≥ 0.8（高优先级保留）────
        allDocs.stream()
                .filter(d -> "reflection".equals(d.getMetadata().get("type")))
                .forEach(d -> {
                    double imp = ((Number) d.getMetadata().get("importance")).doubleValue();
                    assertThat(imp).isGreaterThanOrEqualTo(0.8);
                });
    }

    @Test
    @DisplayName("e2e-08 · L3 · VectorStore 有 3 条 summary 文档，metadata 完整")
    void e2e08_three_summaries_in_pgvector_with_complete_metadata() {
        String sid = sid("l3-meta");

        sendMessages(sid, 29);

        List<Document> docs = awaitVectorDocs(sid, 3, Duration.ofSeconds(20));
        List<Document> summaries = docs.stream()
                .filter(d -> "summary".equals(d.getMetadata().get("type")))
                .toList();

        assertThat(summaries).hasSizeGreaterThanOrEqualTo(3);
        summaries.forEach(doc -> {
            Map<String, Object> meta = doc.getMetadata();
            assertThat(meta)
                    .containsKey("type")
                    .containsKey("sessionId")
                    .containsKey("importance")
                    .containsKey("createdAt");
            assertThat(meta.get("sessionId")).isEqualTo(sid);
        });
    }

    // ═════════════════════════════════════════════════════════════════
    //  L4 — UserProfileService（Redis Hash 真实读写）
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("e2e-09 · L4 · upsertAttribute 真实写入 Redis Hash，getProfile 正确读回")
    void e2e09_user_profile_upsert_and_read_from_redis() {
        String sid = sid("l4-profile");

        userProfileService.upsertAttribute(sid, "reflection",    "用户是 Go 开发者，关注高并发。");
        userProfileService.upsertAttribute(sid, "preferred_lang", "Go");
        userProfileService.upsertAttribute(sid, "focus",         "concurrent programming");

        Map<String, String> profile = userProfileService.getProfile(sid);

        assertThat(profile)
                .containsEntry("preferred_lang", "Go")
                .containsEntry("focus", "concurrent programming");
        assertThat(profile.get("reflection")).contains("Go 开发者");
    }

    @Test
    @DisplayName("e2e-10 · L4 · formatForSystemPrompt 从真实 Redis 读取并格式化为 System Prompt")
    void e2e10_format_for_system_prompt_reads_real_redis() {
        String sid = sid("l4-prompt");

        userProfileService.upsertAttribute(sid, "reflection",    "用户偏好 Go，关注高并发设计。");
        userProfileService.upsertAttribute(sid, "preferred_lang", "Go");

        String prompt = userProfileService.formatForSystemPrompt(sid);

        assertThat(prompt)
                .contains("用户画像")
                .contains("reflection")
                .contains("preferred_lang");
    }

    @Test
    @DisplayName("e2e-11 · L4 · UserProfile 为空时，formatForSystemPrompt 返回空字符串")
    void e2e11_empty_profile_returns_empty_prompt() {
        // 未写入任何 profile 属性的新 sessionId
        String sid = sid("l4-empty");

        String prompt = userProfileService.formatForSystemPrompt(sid);

        assertThat(prompt).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════
    //  Eviction — PGVector 文档定时清理（手动触发）
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("e2e-12 · Eviction · 低重要度超龄文档从真实 PGVector 被删除")
    void e2e12_eviction_deletes_stale_low_importance_docs() {
        long oldTs = Instant.now().minus(200, ChronoUnit.DAYS).toEpochMilli();
        String staleSessionId = "evict-stale-" + UUID.randomUUID().toString().substring(0, 8);

        Document stale = new Document(
                UUID.randomUUID().toString(),
                "超龄低重要度摘要：早已过时的话题讨论",
                Map.of("type", "summary", "importance", 0.05,
                       "createdAt", oldTs, "lastAccessedAt", oldTs,
                       "sessionId", staleSessionId));
        vectorStore.add(List.of(stale));

        // 等待写入完成（VectorStore.add 同步，但防止偶发延迟）
        await().atMost(Durations.TWO_SECONDS).until(() ->
                !vectorStore.similaritySearch(
                        SearchRequest.builder().query("超龄低重要度").topK(5)
                                .similarityThreshold(0.0)
                                .filterExpression(new FilterExpressionBuilder()
                                        .eq("sessionId", staleSessionId).build())
                                .build()).isEmpty());

        evictionPolicyManager.evict();

        List<Document> remaining = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("超龄低重要度")
                        .topK(10)
                        .similarityThreshold(0.0)
                        .filterExpression(new FilterExpressionBuilder()
                                .eq("sessionId", staleSessionId).build())
                        .build());
        assertThat(remaining)
                .as("低重要度超龄文档应被驱逐")
                .isEmpty();
    }

    @Test
    @DisplayName("e2e-13 · Eviction · 高重要度文档即使超龄也不被删除")
    void e2e13_eviction_preserves_high_importance_docs() {
        long oldTs = Instant.now().minus(200, ChronoUnit.DAYS).toEpochMilli();
        String importantSid = "evict-important-" + UUID.randomUUID().toString().substring(0, 8);

        Document important = new Document(
                UUID.randomUUID().toString(),
                "核心用户偏好，高重要度，永久保留",
                Map.of("type", "summary", "importance", 0.9,
                       "createdAt", oldTs, "lastAccessedAt", oldTs,
                       "sessionId", importantSid));
        vectorStore.add(List.of(important));

        evictionPolicyManager.evict();

        List<Document> remaining = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("核心用户偏好")
                        .topK(10)
                        .similarityThreshold(0.0)
                        .filterExpression(new FilterExpressionBuilder()
                                .eq("sessionId", importantSid).build())
                        .build());
        assertThat(remaining)
                .as("高重要度文档不应被驱逐")
                .isNotEmpty();
    }

    @Test
    @DisplayName("e2e-14 · Eviction · reflection 类型文档永不被删除，即使 importance 极低")
    void e2e14_eviction_never_removes_reflection_docs() {
        long oldTs = Instant.now().minus(200, ChronoUnit.DAYS).toEpochMilli();
        String reflectionSid = "evict-reflection-" + UUID.randomUUID().toString().substring(0, 8);

        Document reflectionDoc = new Document(
                UUID.randomUUID().toString(),
                "用户画像反思：用户是 Go 开发者",
                Map.of("type", "reflection", "importance", 0.05,   // 低重要度但 type=reflection
                       "createdAt", oldTs, "lastAccessedAt", oldTs,
                       "sessionId", reflectionSid));
        vectorStore.add(List.of(reflectionDoc));

        evictionPolicyManager.evict();

        List<Document> remaining = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("用户画像")
                        .topK(10)
                        .similarityThreshold(0.0)
                        .filterExpression(new FilterExpressionBuilder()
                                .eq("sessionId", reflectionSid).build())
                        .build());
        assertThat(remaining)
                .as("reflection 文档即使 importance 极低也不应被驱逐")
                .isNotEmpty();
        assertThat(remaining.get(0).getMetadata()).containsEntry("type", "reflection");
    }

    // ═════════════════════════════════════════════════════════════════
    //  完整 E2E 流程（整合验证所有层级）
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("e2e-15 · Full-Pipeline · 29 轮对话驱动 L1→L2→L3→L4 全链路，所有层均有真实数据落盘")
    void e2e15_full_pipeline_all_layers_write_to_real_db() {
        String sid = sid("full");

        // ── 模拟 29 轮技术讨论对话（每条 addMessage 独立） ─────────────
        String[] topics = {
                "goroutine 调度模型",    "channel 有无缓冲区别",   "select 多路复用",
                "context 取消机制",      "sync.WaitGroup 用法",    "sync.Mutex 与 RWMutex",
                "atomic 原子操作",       "内存 happens-before",     "逃逸分析原理",
                "GC 三色标记算法",       "pprof 性能分析",          "基准测试写法",
                "微服务拆分原则",        "gRPC vs REST 选型",       "服务发现机制",
                "分布式链路追踪",        "Kafka 消息幂等",          "Redis 缓存策略",
                "PostgreSQL 索引优化",   "Docker 多阶段构建",       "K8s 滚动更新",
                "CI/CD 流水线设计",      "SLO/SLA 定义方法",        "容量规划思路",
                "代码评审检查清单",      "TDD 与 BDD 的区别",       "可观测性三要素",
                "混沌工程实践",          "技术债务量化管理"
        };
        for (int i = 0; i < 29; i++) {
            memoryService.addMessage(sid, "user",      "请详细介绍" + topics[i]);
            memoryService.addMessage(sid, "assistant", topics[i] + "的核心要点是：【详细解释】");
        }

        // ── L1 验证：真实 Redis 活跃窗口 ≤ 20 条 ─────────────────────
        List<Map<String, String>> active = memoryService.getHistory(sid);
        assertThat(active)
                .as("L1 活跃窗口应 ≤ MAX_HISTORY(20)")
                .hasSizeLessThanOrEqualTo(20);

        // ── L2/L3 验证：等待 PGVector 出现 ≥3 条 summary ──────────────
        List<Document> allDocs = awaitVectorDocs(sid, 3, Duration.ofSeconds(30));
        long summaryCount = allDocs.stream()
                .filter(d -> "summary".equals(d.getMetadata().get("type")))
                .count();
        assertThat(summaryCount)
                .as("L2→L3：PGVector 应有 ≥3 条 summary 文档")
                .isGreaterThanOrEqualTo(3);

        // ── L4 验证：UserProfile 真实写入 Redis ───────────────────────
        await()
                .atMost(Duration.ofSeconds(25))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() ->
                        assertThat(userProfileService.getProfile(sid))
                                .as("L4：UserProfile 应有 reflection 字段")
                                .containsKey("reflection"));

        // ── L4 验证：VectorStore 有 reflection 文档 ──────────────────
        List<Document> withReflection = awaitVectorDocs(sid, 4, Duration.ofSeconds(10));
        boolean hasReflection = withReflection.stream()
                .anyMatch(d -> "reflection".equals(d.getMetadata().get("type")));
        assertThat(hasReflection)
                .as("L4：VectorStore 中应有 type=reflection 文档")
                .isTrue();

        // ── 汇总打印（帮助人工回溯）─────────────────────────────────
        Map<String, String> finalProfile = userProfileService.getProfile(sid);
        System.out.printf("%n[E2E Summary] session=%s%n  L1 active=%d  L2/L3 docs=%d%n  L4 profile=%s%n",
                sid, active.size(), withReflection.size(), finalProfile.get("reflection"));
    }

    // ═════════════════════════════════════════════════════════════════
    //  辅助方法
    // ═════════════════════════════════════════════════════════════════

    /** 生成唯一 sessionId 并注册到清理列表 */
    private String sid(String label) {
        String id = "e2e-" + label + "-" + UUID.randomUUID().toString().substring(0, 8);
        usedSessionIds.add(id);
        return id;
    }

    /** 批量向 sessionId 发送消息，role 交替 user/assistant */
    private void sendMessages(String sessionId, int count) {
        for (int i = 1; i <= count; i++) {
            String role = (i % 2 == 1) ? "user" : "assistant";
            memoryService.addMessage(sessionId, role,
                    "消息 " + i + "：Go 并发编程中关于 goroutine 调度的深度探讨");
        }
    }

    /**
     * 等待 VectorStore 中指定 sessionId 至少有 minCount 条文档后返回。
     * 使用 Awaitility 轮询，绕过 @Async 的时序不确定性。
     */
    private List<Document> awaitVectorDocs(String sessionId, int minCount, Duration timeout) {
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        SearchRequest req = SearchRequest.builder()
                .query("摘要 技术讨论")
                .topK(50)
                .similarityThreshold(0.0)
                .filterExpression(fb.eq("sessionId", sessionId).build())
                .build();

        await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(500))
                .until(() -> vectorStore.similaritySearch(req).size() >= minCount);

        return vectorStore.similaritySearch(req);
    }
}
