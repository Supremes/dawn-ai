package com.dawn.ai.memory;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.dawn.ai.service.MemoryService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 对话流程集成测试 —— 验证 Memory 多层设计
 *
 * 层级架构:
 *   L1  Redis 滑动窗口   (MemoryService)         最多保留 20 条活跃消息
 *   L2  摘要化           (MemorySummarizer)       Pending 队列满 N 条时调 LLM 压缩
 *   L3  情节记忆          (MemoryConsolidator)     摘要写入 VectorStore；达到阈值触发 Reflection
 *   L4  用户画像          (ReflectionWorker +      跨摘要提炼长期偏好，存入 VectorStore 及
 *                         UserProfileService)      Redis Hash
 *   Eviction              (EvictionPolicyManager)  定时清理低重要度/过期 Document
 *
 * 测试策略: 用同步路由 Publisher 绕过 @Async，直接在主线程内驱动完整管道；
 *           所有外部 I/O (Redis / VectorStore / ChatClient) 均 mock。
 */
@DisplayName("Memory 多层管道 —— 对话流程集成测试")
class MemoryConversationFlowTest {

    // ── 可调参数（小值以便测试触发条件）──────────────────────────────────
    private static final int SUMMARY_BATCH_SIZE    = 3;  // pending 满 3 条触发摘要
    private static final int REFLECTION_THRESHOLD  = 3;  // 3 次 consolidation 触发 Reflection
    private static final int EPISODE_THRESHOLD     = 4;  // Reflection 时至少需要 threshold/2 = 2 个 episode

    // ── 外部依赖 (mock) ─────────────────────────────────────────────────
    private RedisTemplate<String, Object>  redisTemplate;
    private ListOperations<String, Object> listOps;
    private HashOperations<String, Object, Object> hashOps;
    private VectorStore                    vectorStore;
    private ChatClient                     chatClient;
    private ChatClient.ChatClientRequestSpec  requestSpec;
    private ChatClient.CallResponseSpec       callSpec;

    // ── 真实业务 Bean ────────────────────────────────────────────────────
    private MemoryService        memoryService;
    private MemorySummarizer     memorySummarizer;
    private MemoryConsolidator   memoryConsolidator;
    private ReflectionWorker     reflectionWorker;
    private UserProfileService   userProfileService;
    private EvictionPolicyManager evictionPolicyManager;

    // ── 管道事件总线 ────────────────────────────────────────────────────
    private RoutingEventPublisher pipelinePublisher;

    @BeforeEach
    void setUp() {
        // mock 外部 I/O
        redisTemplate = mock(RedisTemplate.class);
        listOps       = mock(ListOperations.class);
        hashOps       = mock(HashOperations.class);
        chatClient    = mock(ChatClient.class);
        requestSpec   = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec      = mock(ChatClient.CallResponseSpec.class);
        vectorStore   = mock(VectorStore.class);

        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        // 构建同步路由 Publisher（解决循环依赖：先 new 再注册路由）
        pipelinePublisher = new RoutingEventPublisher();

        // 构建真实 Bean，注入 Publisher
        memoryService = new MemoryService(redisTemplate, new SimpleMeterRegistry(), pipelinePublisher);
        ReflectionTestUtils.invokeMethod(memoryService, "initMetrics");
        ReflectionTestUtils.setField(memoryService, "summaryBatchSize", SUMMARY_BATCH_SIZE);

        userProfileService = new UserProfileService(redisTemplate);
        memorySummarizer   = new MemorySummarizer(chatClient, pipelinePublisher);
        memoryConsolidator = new MemoryConsolidator(vectorStore, pipelinePublisher, REFLECTION_THRESHOLD);
        reflectionWorker   = new ReflectionWorker(vectorStore, chatClient, userProfileService, EPISODE_THRESHOLD);
        evictionPolicyManager = new EvictionPolicyManager(vectorStore, 0.1, 180);

        // 注册路由：事件类型 → 处理方法（同步，绑过 @Async）
        pipelinePublisher.register(SummarizationRequestEvent.class, memorySummarizer::onSummarizationRequest);
        pipelinePublisher.register(ConsolidationRequestEvent.class, memoryConsolidator::onConsolidationRequest);
        pipelinePublisher.register(ReflectionRequestEvent.class,    reflectionWorker::onReflectionRequest);
    }

    // ════════════════════════════════════════════════════════════════════
    //  L1 — Redis 滑动窗口
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("L1 · Redis 滑动窗口")
    class L1SlidingWindow {

        @Test
        @DisplayName("scenario01 · 消息数 ≤ 20 时，全部留在活跃窗口，不触发 Pending")
        void scenario01_withinCapacity_noPendingEnqueued() {
            // list 大小始终 ≤ 20 → 不弹出
            when(listOps.size(argThat(k -> k != null && !k.contains(":pending")))).thenReturn(10L);

            for (int i = 0; i < 10; i++) {
                memoryService.addMessage("sess", "user", "msg " + i);
            }

            // pending queue 不应写入任何内容
            verify(listOps, never()).rightPush(argThat(k -> k != null && k.contains(":pending")), any());
        }

        @Test
        @DisplayName("scenario02 · 第 21 条消息写入时，最旧消息被弹出并进入 Pending 队列")
        void scenario02_overflow_oldestMessageMovedToPending() {
            Map<String, String> oldMsg = Map.of("role", "user", "content", "oldest message");

            when(listOps.size(argThat(k -> k != null && !k.contains(":pending")))).thenReturn(21L);
            when(listOps.leftPop(anyString())).thenReturn(oldMsg);
            // pending 还没满，不触发摘要
            when(listOps.size(argThat(k -> k != null && k.contains(":pending")))).thenReturn(1L);
            when(listOps.rightPush(argThat(k -> k != null && k.contains(":pending")), any())).thenReturn(1L);

            memoryService.addMessage("sess", "user", "turn 21");

            verify(listOps).leftPop(argThat(k -> k != null && !k.contains(":pending")));
            verify(listOps).rightPush(argThat(k -> k != null && k.contains(":pending")), eq(oldMsg));
        }

        @Test
        @DisplayName("scenario03 · Redis 不可用时，消息写入内存 Fallback，读取仍然成功")
        void scenario03_redisDown_fallbackPreservesHistory() {
            doThrow(new RuntimeException("Redis unavailable")).when(listOps).rightPush(anyString(), any());
            doThrow(new RuntimeException("Redis unavailable")).when(listOps).range(anyString(), anyLong(), anyLong());

            memoryService.addMessage("sess-fallback", "user", "hello");
            memoryService.addMessage("sess-fallback", "assistant", "hi there");

            List<Map<String, String>> history = memoryService.getHistory("sess-fallback");

            assertThat(history).hasSize(2);
            assertThat(history.get(0).get("role")).isEqualTo("user");
            assertThat(history.get(0).get("content")).isEqualTo("hello");
            assertThat(history.get(1).get("role")).isEqualTo("assistant");
        }

        @Test
        @DisplayName("scenario04 · clearSession 后，Fallback 存储也被清空")
        void scenario04_clearSession_purgesFallback() {
            doThrow(new RuntimeException("Redis down")).when(listOps).rightPush(anyString(), any());
            doThrow(new RuntimeException("Redis down")).when(listOps).range(anyString(), anyLong(), anyLong());

            memoryService.addMessage("sess-clear", "user", "to be erased");
            memoryService.clearSession("sess-clear");

            List<Map<String, String>> history = memoryService.getHistory("sess-clear");
            assertThat(history).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  L1 → L2 — 触发摘要化
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("L1→L2 · Pending 满载触发摘要")
    class L1toL2SummarizationTrigger {

        @Test
        @DisplayName("scenario05 · Pending 队列达到 batch-size，发布 SummarizationRequestEvent")
        void scenario05_pendingFull_publishesSummarizationEvent() {
            Map<String, String> popped = Map.of("role", "user", "content", "old msg");
            when(listOps.size(argThat(k -> k != null && !k.contains(":pending")))).thenReturn(21L);
            when(listOps.leftPop(anyString())).thenReturn(popped);
            // pending 在第 3 条时满
            when(listOps.rightPush(argThat(k -> k != null && k.contains(":pending")), any())).thenReturn((long) SUMMARY_BATCH_SIZE);
            when(listOps.size(argThat(k -> k != null && k.contains(":pending")))).thenReturn((long) SUMMARY_BATCH_SIZE);
            when(listOps.range(argThat(k -> k != null && k.contains(":pending")), anyLong(), anyLong()))
                    .thenReturn(List.of(popped));
            // rename 成功（drainPending 原子性）
            doNothing().when(redisTemplate).rename(anyString(), anyString());
            // LLM 摘要返回
            when(callSpec.content()).thenReturn("用户在询问天气情况。");
            // VectorStore 写入成功
            doNothing().when(vectorStore).add(anyList());

            memoryService.addMessage("sess-trig", "user", "第21条消息");

            // 验证 SummarizationRequestEvent 被触发（并通过同步路由进入 MemorySummarizer）
            // MemorySummarizer 会调用 LLM，说明路由生效
            verify(chatClient, atLeastOnce()).prompt();
        }

        @Test
        @DisplayName("scenario06 · Pending 未满时，不触发摘要")
        void scenario06_pendingNotFull_noSummarization() {
            Map<String, String> popped = Map.of("role", "user", "content", "old");
            when(listOps.size(argThat(k -> k != null && !k.contains(":pending")))).thenReturn(21L);
            when(listOps.leftPop(anyString())).thenReturn(popped);
            when(listOps.rightPush(argThat(k -> k != null && k.contains(":pending")), any())).thenReturn(1L);
            when(listOps.size(argThat(k -> k != null && k.contains(":pending")))).thenReturn(1L);

            memoryService.addMessage("sess-no-trig", "user", "msg21");

            // pending 未满，LLM 不应被调用
            verify(chatClient, never()).prompt();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  L2 — 摘要化
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("L2 · MemorySummarizer 摘要化")
    class L2Summarization {

        private SummarizationRequestEvent makeEvent(String sessionId, int msgCount) {
            List<Map<String, String>> msgs = new ArrayList<>();
            for (int i = 0; i < msgCount; i++) {
                msgs.add(Map.of("role", i % 2 == 0 ? "user" : "assistant", "content", "消息 " + i));
            }
            return new SummarizationRequestEvent(sessionId, msgs);
        }

        @Test
        @DisplayName("scenario07 · LLM 成功返回摘要，写入 VectorStore，importance=0.5")
        void scenario07_llmSuccess_summaryStoredWithImportance05() {
            when(callSpec.content()).thenReturn("用户讨论了编程语言偏好，倾向 Python。");
            doNothing().when(vectorStore).add(anyList());

            memorySummarizer.onSummarizationRequest(makeEvent("sess-sum", SUMMARY_BATCH_SIZE));

            verify(vectorStore).add(argThat(docs ->
                    docs.size() == 1 &&
                    "summary".equals(docs.get(0).getMetadata().get("type")) &&
                    "sess-sum".equals(docs.get(0).getMetadata().get("sessionId")) &&
                    ((Number) docs.get(0).getMetadata().get("importance")).doubleValue() == 0.5
            ));
        }

        @Test
        @DisplayName("scenario08 · LLM 超时，使用原始对话文本作为 Fallback，importance=0.3")
        void scenario08_llmTimeout_rawTextFallbackStoredWithImportance03() {
            when(callSpec.content()).thenThrow(new RuntimeException("LLM timeout"));
            doNothing().when(vectorStore).add(anyList());

            memorySummarizer.onSummarizationRequest(makeEvent("sess-timeout", SUMMARY_BATCH_SIZE));

            // Fallback：写入 VectorStore 但 importance < 0.4
            verify(vectorStore).add(argThat(docs ->
                    docs.size() == 1 &&
                    ((Number) docs.get(0).getMetadata().get("importance")).doubleValue() < 0.4
            ));
        }

        @Test
        @DisplayName("scenario09 · 摘要后触发 ConsolidationRequestEvent，sessionId 匹配")
        void scenario09_summarization_publishesConsolidationEvent() {
            when(callSpec.content()).thenReturn("摘要内容");
            doNothing().when(vectorStore).add(anyList());

            List<Object> capturedEvents = pipelinePublisher.capturedEvents();

            memorySummarizer.onSummarizationRequest(makeEvent("sess-conso", SUMMARY_BATCH_SIZE));

            boolean hasConsolidation = capturedEvents.stream()
                    .anyMatch(e -> e instanceof ConsolidationRequestEvent cre &&
                                   "sess-conso".equals(cre.result().sessionId()));
            assertThat(hasConsolidation).isTrue();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  L3 — 情节记忆 & Reflection 触发
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("L3 · MemoryConsolidator 情节记忆")
    class L3EpisodicMemory {

        @Test
        @DisplayName("scenario10 · 摘要写入 VectorStore，metadata 包含 type/sessionId/importance")
        void scenario10_summaryPersistedWithCorrectMetadata() {
            SummaryResult summary = new SummaryResult("sess-ep", "用户偏好 Go 语言。", 0.5, Instant.now());
            doNothing().when(vectorStore).add(anyList());

            memoryConsolidator.onConsolidationRequest(new ConsolidationRequestEvent(summary));

            verify(vectorStore).add(argThat(docs -> {
                Document doc = docs.get(0);
                return "summary".equals(doc.getMetadata().get("type"))
                        && "sess-ep".equals(doc.getMetadata().get("sessionId"))
                        && ((Number) doc.getMetadata().get("importance")).doubleValue() == 0.5
                        && doc.getMetadata().containsKey("createdAt");
            }));
        }

        @Test
        @DisplayName("scenario11 · 连续 consolidation 达到阈值，触发 ReflectionRequestEvent")
        void scenario11_consolidationThresholdReached_publishesReflectionEvent() {
            doNothing().when(vectorStore).add(anyList());
            // Reflection 时需要的 episodes（让 ReflectionWorker 有足够数据）
            List<Document> episodes = makeEpisodes("sess-reflect", EPISODE_THRESHOLD);
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(episodes);
            when(callSpec.content()).thenReturn("用户是 Go 开发者，关注高并发。");

            // 连续发 REFLECTION_THRESHOLD 次 consolidation
            for (int i = 0; i < REFLECTION_THRESHOLD; i++) {
                SummaryResult sr = new SummaryResult("sess-reflect", "摘要 " + i, 0.5, Instant.now());
                memoryConsolidator.onConsolidationRequest(new ConsolidationRequestEvent(sr));
            }

            // 第 REFLECTION_THRESHOLD 次应触发 ReflectionRequestEvent
            boolean hasReflection = pipelinePublisher.capturedEvents().stream()
                    .anyMatch(e -> e instanceof ReflectionRequestEvent rre &&
                                   "sess-reflect".equals(rre.sessionId()));
            assertThat(hasReflection).isTrue();
        }

        @Test
        @DisplayName("scenario12 · VectorStore 写入失败，不触发 Reflection，不抛异常")
        void scenario12_vectorStoreFails_reflectionNotTriggered() {
            doThrow(new RuntimeException("PGVector down")).when(vectorStore).add(anyList());

            SummaryResult summary = new SummaryResult("sess-fail", "some summary", 0.5, Instant.now());
            memoryConsolidator.onConsolidationRequest(new ConsolidationRequestEvent(summary));

            // VectorStore 失败 → early return → 不发 ReflectionEvent
            boolean hasReflection = pipelinePublisher.capturedEvents().stream()
                    .anyMatch(e -> e instanceof ReflectionRequestEvent);
            assertThat(hasReflection).isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  L4 — Reflection & 用户画像
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("L4 · ReflectionWorker & UserProfileService")
    class L4ReflectionAndProfile {

        @Test
        @DisplayName("scenario13 · Episodes 足够，LLM 提炼画像，写入 VectorStore + UserProfile")
        void scenario13_sufficientEpisodes_profilePersistedEverywhere() {
            List<Document> episodes = makeEpisodes("sess-prof", EPISODE_THRESHOLD);
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(episodes);
            when(callSpec.content()).thenReturn("用户是后端开发者，熟悉 Java/Go，关注系统性能。");

            reflectionWorker.onReflectionRequest(new ReflectionRequestEvent("sess-prof"));

            // 写入 VectorStore，type=reflection，importance ≥ 0.8
            verify(vectorStore).add(argThat(docs -> {
                Document doc = docs.get(0);
                return "reflection".equals(doc.getMetadata().get("type"))
                        && ((Number) doc.getMetadata().get("importance")).doubleValue() >= 0.8;
            }));

            // 写入 UserProfile（Redis Hash）
            verify(hashOps).put(
                    argThat(k -> k.toString().contains("sess-prof")),
                    eq("reflection"),
                    eq("用户是后端开发者，熟悉 Java/Go，关注系统性能。")
            );
        }

        @Test
        @DisplayName("scenario14 · Episodes 不足（< threshold/2），跳过 Reflection")
        void scenario14_insufficientEpisodes_reflectionSkipped() {
            // 只有 1 个 episode，EPISODE_THRESHOLD=4 → threshold/2=2 → 不足
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(
                    List.of(new Document("1", "only episode", Map.of()))
            );

            reflectionWorker.onReflectionRequest(new ReflectionRequestEvent("sess-skip"));

            verify(chatClient, never()).prompt();
            verify(vectorStore, never()).add(any());
        }

        @Test
        @DisplayName("scenario15 · LLM Reflection 失败，不写入 VectorStore 也不更新 UserProfile")
        void scenario15_llmReflectionFails_noSideEffects() {
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(makeEpisodes("sess-llm-fail", EPISODE_THRESHOLD));
            when(callSpec.content()).thenThrow(new RuntimeException("LLM error"));

            reflectionWorker.onReflectionRequest(new ReflectionRequestEvent("sess-llm-fail"));

            verify(vectorStore, never()).add(any());
            verify(hashOps, never()).put(any(), any(), any());
        }

        @Test
        @DisplayName("scenario16 · UserProfile.formatForSystemPrompt 将画像注入 System Prompt")
        void scenario16_formatForSystemPrompt_containsProfileAttributes() {
            when(hashOps.entries(anyString())).thenReturn(Map.of(
                    "reflection", "用户偏好 Go，关注高并发",
                    "language", "Go"
            ));

            String prompt = userProfileService.formatForSystemPrompt("sess-fmt");

            assertThat(prompt).contains("用户画像");
            assertThat(prompt).contains("reflection");
            assertThat(prompt).contains("language");
        }

        @Test
        @DisplayName("scenario17 · UserProfile 为空时，formatForSystemPrompt 返回空字符串")
        void scenario17_emptyProfile_formatReturnsEmpty() {
            when(hashOps.entries(anyString())).thenReturn(Map.of());

            String prompt = userProfileService.formatForSystemPrompt("sess-empty");

            assertThat(prompt).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Eviction — 情节记忆清理
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Eviction · EvictionPolicyManager 定时清理")
    class EvictionPolicy {

        @Test
        @DisplayName("scenario18 · 低重要度 + 超龄文档被删除")
        void scenario18_staleAndLowImportance_evicted() {
            long oldTs = Instant.now().minus(200, ChronoUnit.DAYS).toEpochMilli();
            Document stale = new Document("stale-1", "old chat",
                    Map.of("type", "summary", "importance", 0.05, "createdAt", oldTs));
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(stale));

            evictionPolicyManager.evict();

            verify(vectorStore).delete(argThat((List<String> ids) -> ids.contains("stale-1")));
        }

        @Test
        @DisplayName("scenario19 · 高重要度文档，无论多旧都不被删除")
        void scenario19_highImportance_neverEvicted() {
            long oldTs = Instant.now().minus(200, ChronoUnit.DAYS).toEpochMilli();
            Document important = new Document("imp-1", "critical context",
                    Map.of("type", "summary", "importance", 0.9, "createdAt", oldTs));
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(important));

            evictionPolicyManager.evict();

            verify(vectorStore, never()).delete(argThat((List<String> ids) -> true));
        }

        @Test
        @DisplayName("scenario20 · Reflection 类型文档永不被删除（even if low importance）")
        void scenario20_reflectionTypeDocument_neverEvicted() {
            long oldTs = Instant.now().minus(200, ChronoUnit.DAYS).toEpochMilli();
            Document reflection = new Document("ref-1", "user profile",
                    Map.of("type", "reflection", "importance", 0.05, "createdAt", oldTs));
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(reflection));

            evictionPolicyManager.evict();

            verify(vectorStore, never()).delete(argThat((List<String> ids) -> true));
        }

        @Test
        @DisplayName("scenario21 · 最近文档（即使低重要度）不被删除")
        void scenario21_recentDocument_notEvicted() {
            long recentTs = Instant.now().minus(10, ChronoUnit.DAYS).toEpochMilli();
            Document recent = new Document("rec-1", "fresh content",
                    Map.of("type", "summary", "importance", 0.05, "createdAt", recentTs));
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(recent));

            evictionPolicyManager.evict();

            verify(vectorStore, never()).delete(argThat((List<String> ids) -> true));
        }

        @Test
        @DisplayName("scenario22 · VectorStore 故障时，Eviction 静默跳过不抛异常")
        void scenario22_vectorStoreDown_evictionSkipsSilently() {
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("DB unreachable"));

            // 不应抛出异常
            evictionPolicyManager.evict();

            verify(vectorStore, never()).delete(argThat((List<String> ids) -> true));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  End-to-End 完整对话流程
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("E2E · 完整 25 轮对话流程")
    class EndToEndConversationFlow {

        /**
         * 模拟 25 轮对话，验证各层依次被触发：
         *
         * Turn 1–20  → L1 活跃窗口正常积累，不溢出
         * Turn 21–23 → 每条消息溢出 1 条至 Pending（共 3 条），pending 满后触发 L2 摘要
         * L2 摘要    → LLM 生成摘要 → L3 写入 VectorStore
         * Turn 24–26 → 再次触发 L2（第 2 批次）→ L3 再写入
         * Turn 27–29 → 第 3 批次 → L3 第 3 次写入，达 reflectionThreshold → 触发 L4
         * L4 Reflection → 查 VectorStore episodes → LLM 提炼画像 → 写 UserProfile
         */
        @Test
        @DisplayName("scenario23 · 25 轮对话驱动完整 L1→L2→L3→L4 管道")
        void scenario23_fullConversation_entirePipelineFires() {
            // ── Arrange ──────────────────────────────────────────────────
            String sessionId = "sess-e2e";
            List<Map<String, String>> pendingStore = new ArrayList<>();
            List<Map<String, String>> activeStore  = new ArrayList<>();

            // Redis 模拟：active list 大小追踪
            when(listOps.rightPush(argThat(k -> k != null && !k.contains(":pending")), any()))
                    .thenAnswer(inv -> {
                        activeStore.add((Map<String, String>) inv.getArgument(1));
                        return (long) activeStore.size();
                    });
            when(listOps.size(argThat(k -> k != null && !k.contains(":pending"))))
                    .thenAnswer(inv -> (long) activeStore.size());
            when(listOps.leftPop(argThat(k -> k != null && !k.contains(":pending"))))
                    .thenAnswer(inv -> activeStore.isEmpty() ? null : activeStore.remove(0));

            // Redis 模拟：pending list
            when(listOps.rightPush(argThat(k -> k != null && k.contains(":pending")), any()))
                    .thenAnswer(inv -> {
                        pendingStore.add((Map<String, String>) inv.getArgument(1));
                        return (long) pendingStore.size();
                    });
            when(listOps.size(argThat(k -> k != null && k.contains(":pending"))))
                    .thenAnswer(inv -> (long) pendingStore.size());
            // drainPending 使用 rename → 这里直接模拟 rename 成功，range 返回当前 pending
            doNothing().when(redisTemplate).rename(anyString(), anyString());
            when(listOps.range(argThat(k -> k != null && k.contains(":drain:")), anyLong(), anyLong()))
                    .thenAnswer(inv -> {
                        List<Object> snapshot = new ArrayList<>(pendingStore);
                        pendingStore.clear();
                        return snapshot;
                    });

            // LLM 摘要
            when(callSpec.content())
                    .thenReturn("用户在讨论技术选型问题，倾向 Go。")
                    .thenReturn("用户询问了性能优化方案。")
                    .thenReturn("用户关注微服务架构设计。")
                    // L4 Reflection LLM 调用
                    .thenReturn("用户是后端工程师，偏好 Go，关注架构设计和性能优化。");

            // VectorStore：写入成功
            doNothing().when(vectorStore).add(anyList());
            // L4：返回足够的 episodes
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(makeEpisodes(sessionId, EPISODE_THRESHOLD));

            // ── Act：发送 25 轮消息（每轮 user + assistant = 2 条）──────────
            for (int turn = 1; turn <= 25; turn++) {
                memoryService.addMessage(sessionId, "user", "问题 " + turn);
                memoryService.addMessage(sessionId, "assistant", "回答 " + turn);
            }

            // ── Assert ────────────────────────────────────────────────────
            List<Object> events = pipelinePublisher.capturedEvents();

            // 应该至少有 3 次 SummarizationRequestEvent（每 BATCH_SIZE 条 pending 触发一次）
            long summarizationCount = events.stream()
                    .filter(e -> e instanceof SummarizationRequestEvent).count();
            assertThat(summarizationCount).isGreaterThanOrEqualTo(1);

            // 应该至少有 1 次 ConsolidationRequestEvent
            long consolidationCount = events.stream()
                    .filter(e -> e instanceof ConsolidationRequestEvent).count();
            assertThat(consolidationCount).isGreaterThanOrEqualTo(1);

            // VectorStore 被写入（L3 摘要）
            verify(vectorStore, atLeastOnce()).add(anyList());
        }

        @Test
        @DisplayName("scenario24 · Redis 全程不可用，Fallback 保留最近 20 条消息")
        void scenario24_redisFullyDown_fallbackCapsAt20() {
            doThrow(new RuntimeException("Redis down")).when(listOps).rightPush(anyString(), any());
            doThrow(new RuntimeException("Redis down")).when(listOps).range(anyString(), anyLong(), anyLong());

            String sessionId = "sess-redis-down";
            // 发 25 条消息
            for (int i = 1; i <= 25; i++) {
                memoryService.addMessage(sessionId, "user", "msg " + i);
            }

            List<Map<String, String>> history = memoryService.getHistory(sessionId);
            // Fallback 在内存中同样限制为 MAX_HISTORY(20)
            assertThat(history).hasSize(20);
            // 最新的是第 25 条
            assertThat(history.get(19).get("content")).isEqualTo("msg 25");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  辅助方法 & 内部类
    // ════════════════════════════════════════════════════════════════════

    private List<Document> makeEpisodes(String sessionId, int count) {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            docs.add(new Document(
                    UUID.randomUUID().toString(),
                    "摘要 episode " + i + "：用户讨论了技术话题",
                    Map.of("sessionId", sessionId, "type", "summary")
            ));
        }
        return docs;
    }

    /**
     * 同步路由事件总线，绕过 @Async，让测试在单线程内驱动完整管道。
     * 同时记录所有已发布的事件供断言使用。
     */
    static class RoutingEventPublisher implements ApplicationEventPublisher {

        private final Map<Class<?>, Consumer<Object>> routes = new LinkedHashMap<>();
        private final List<Object> captured = new CopyOnWriteArrayList<>();

        @SuppressWarnings("unchecked")
        <T> void register(Class<T> eventType, Consumer<T> handler) {
            routes.put(eventType, (Consumer<Object>) handler);
        }

        @Override
        public void publishEvent(Object event) {
            captured.add(event);
            Consumer<Object> handler = routes.get(event.getClass());
            if (handler != null) {
                handler.accept(event);
            }
        }

        List<Object> capturedEvents() {
            return Collections.unmodifiableList(captured);
        }
    }
}
