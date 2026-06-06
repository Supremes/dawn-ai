package com.dawn.ai.memory;

import com.dawn.ai.memory.entity.MemoryEntity;
import com.dawn.ai.memory.repository.MemoryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
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
 * 对话流程集成测试 —— 验证 Memory 多层设计（mem0 对齐版）
 *
 * 层级架构:
 *   L1  Redis 滑动窗口   (MemoryService)         最多保留 20 条活跃消息
 *   L2  摘要化           (MemorySummarizer)       Pending 队列满 N 条时调 LLM 压缩，发布 EpisodicMemoryEvent
 *   L3  情节记忆          (MemoryConsolidator)     通过 MemoryManager 持久化情节记忆；达到阈值触发 Reflection
 *   L4  用户画像          (ReflectionWorker +      跨情节提炼长期偏好，存入 MemoryManager 及
 *                         UserProfileService)      Redis Hash
 *   Eviction              (EvictionPolicyManager)  定时清理低重要度/过期记忆
 *
 * 测试策略: 用同步路由 Publisher 绕过 @Async，直接在主线程内驱动完整管道；
 *           所有外部依赖 (Redis / MemoryManager / ChatClient) 均 mock。
 */
@DisplayName("Memory 多层管道 —— 对话流程集成测试")
class MemoryConversationFlowTest {

    // ── 可调参数（小值以便测试触发条件）──────────────────────────────────
    private static final int SUMMARY_BATCH_SIZE    = 3;  // pending 满 3 条触发摘要
    private static final int REFLECTION_THRESHOLD  = 3;  // 3 次情节记忆触发 Reflection
    private static final int EPISODE_THRESHOLD     = 4;  // Reflection 时至少需要 threshold/2 = 2 个 episode
    private static final String USER_ID            = "user-1";

    // ── 外部依赖 (mock) ─────────────────────────────────────────────────
    private RedisTemplate<String, Object>  redisTemplate;
    private ListOperations<String, Object> listOps;
    private HashOperations<String, Object, Object> hashOps;
    private MemoryManager                  memoryManager;
    private MemoryRepository               memoryRepository;
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
        // mock 外部依赖
        redisTemplate  = mock(RedisTemplate.class);
        listOps        = mock(ListOperations.class);
        hashOps        = mock(HashOperations.class);
        chatClient     = mock(ChatClient.class);
        requestSpec    = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec       = mock(ChatClient.CallResponseSpec.class);
        memoryManager  = mock(MemoryManager.class);
        memoryRepository = mock(MemoryRepository.class);

        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        // 构建同步路由 Publisher
        pipelinePublisher = new RoutingEventPublisher();

        // 构建真实 Bean，注入 Publisher
        memoryService = new MemoryService(redisTemplate, new SimpleMeterRegistry(), pipelinePublisher);
        ReflectionTestUtils.invokeMethod(memoryService, "initMetrics");
        ReflectionTestUtils.setField(memoryService, "summaryBatchSize", SUMMARY_BATCH_SIZE);

        userProfileService = new UserProfileService(redisTemplate);
        memorySummarizer   = new MemorySummarizer(chatClient, pipelinePublisher);
        memoryConsolidator = new MemoryConsolidator(memoryManager, pipelinePublisher, REFLECTION_THRESHOLD);
        reflectionWorker   = new ReflectionWorker(memoryManager, chatClient, userProfileService, EPISODE_THRESHOLD);
        evictionPolicyManager = new EvictionPolicyManager(memoryManager, memoryRepository, 0.1, 180);

        // 注册路由：事件类型 → 处理方法（同步，绕过 @Async）
        pipelinePublisher.register(SummarizationRequestEvent.class, memorySummarizer::onSummarizationRequest);
        pipelinePublisher.register(EpisodicMemoryEvent.class,       memoryConsolidator::onEpisodicMemory);
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
                memoryService.addMessage("sess", USER_ID, "user", "msg " + i);
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

            memoryService.addMessage("sess", USER_ID, "user", "turn 21");

            verify(listOps).leftPop(argThat(k -> k != null && !k.contains(":pending")));
            verify(listOps).rightPush(argThat(k -> k != null && k.contains(":pending")), eq(oldMsg));
        }

        @Test
        @DisplayName("scenario03 · Redis 不可用时，消息写入内存 Fallback，读取仍然成功")
        void scenario03_redisDown_fallbackPreservesHistory() {
            doThrow(new RuntimeException("Redis unavailable")).when(listOps).rightPush(anyString(), any());
            doThrow(new RuntimeException("Redis unavailable")).when(listOps).range(anyString(), anyLong(), anyLong());

            memoryService.addMessage("sess-fallback", USER_ID, "user", "hello");
            memoryService.addMessage("sess-fallback", USER_ID, "assistant", "hi there");

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

            memoryService.addMessage("sess-clear", USER_ID, "user", "to be erased");
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
            when(listOps.range(argThat(k -> k != null && k.contains(":drain:")), anyLong(), anyLong()))
                    .thenReturn(List.of(popped));
            // rename 成功（drainPending 原子性）
            doNothing().when(redisTemplate).rename(anyString(), anyString());
            // LLM 摘要返回
            when(callSpec.content()).thenReturn("用户在询问天气情况。");

            memoryService.addMessage("sess-trig", USER_ID, "user", "第21条消息");

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

            memoryService.addMessage("sess-no-trig", USER_ID, "user", "msg21");

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
            return new SummarizationRequestEvent(sessionId, USER_ID, msgs);
        }

        @Test
        @DisplayName("scenario07 · LLM 成功返回摘要，作为情节记忆持久化，importance=0.5")
        void scenario07_llmSuccess_episodicPersistedWithImportance05() {
            when(callSpec.content()).thenReturn("用户讨论了编程语言偏好，倾向 Python。");

            memorySummarizer.onSummarizationRequest(makeEvent("sess-sum", SUMMARY_BATCH_SIZE));

            // MemorySummarizer 发布 EpisodicMemoryEvent，经路由由 Consolidator 落库
            verify(memoryManager).add(eq(USER_ID), eq("sess-sum"),
                    eq("用户讨论了编程语言偏好，倾向 Python。"), eq(MemoryType.EPISODIC), eq(0.5));
        }

        @Test
        @DisplayName("scenario08 · LLM 超时，使用原始对话文本作为 Fallback，importance=0.3")
        void scenario08_llmTimeout_rawTextFallbackStoredWithImportance03() {
            when(callSpec.content()).thenThrow(new RuntimeException("LLM timeout"));

            memorySummarizer.onSummarizationRequest(makeEvent("sess-timeout", SUMMARY_BATCH_SIZE));

            // Fallback：importance < 0.4
            verify(memoryManager).add(eq(USER_ID), eq("sess-timeout"),
                    anyString(), eq(MemoryType.EPISODIC), doubleThat(v -> v < 0.4));
        }

        @Test
        @DisplayName("scenario09 · 摘要后发布 EpisodicMemoryEvent，sessionId 匹配")
        void scenario09_summarization_publishesEpisodicEvent() {
            when(callSpec.content()).thenReturn("摘要内容");

            memorySummarizer.onSummarizationRequest(makeEvent("sess-conso", SUMMARY_BATCH_SIZE));

            boolean hasEpisodic = pipelinePublisher.capturedEvents().stream()
                    .anyMatch(e -> e instanceof EpisodicMemoryEvent eme &&
                                   "sess-conso".equals(eme.sessionId()));
            assertThat(hasEpisodic).isTrue();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  L3 — 情节记忆 & Reflection 触发
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("L3 · MemoryConsolidator 情节记忆")
    class L3EpisodicMemory {

        @Test
        @DisplayName("scenario10 · 情节摘要通过 MemoryManager 持久化为 EPISODIC，importance 透传")
        void scenario10_summaryPersistedAsEpisodic() {
            memoryConsolidator.onEpisodicMemory(
                    new EpisodicMemoryEvent("sess-ep", USER_ID, "用户偏好 Go 语言。", 0.5));

            verify(memoryManager).add(USER_ID, "sess-ep", "用户偏好 Go 语言。", MemoryType.EPISODIC, 0.5);
        }

        @Test
        @DisplayName("scenario11 · 连续情节记忆达到阈值，触发 ReflectionRequestEvent")
        void scenario11_consolidationThresholdReached_publishesReflectionEvent() {
            // 连续发 REFLECTION_THRESHOLD 次情节记忆
            for (int i = 0; i < REFLECTION_THRESHOLD; i++) {
                memoryConsolidator.onEpisodicMemory(
                        new EpisodicMemoryEvent("sess-reflect", USER_ID, "摘要 " + i, 0.5));
            }

            boolean hasReflection = pipelinePublisher.capturedEvents().stream()
                    .anyMatch(e -> e instanceof ReflectionRequestEvent rre &&
                                   "sess-reflect".equals(rre.sessionId()));
            assertThat(hasReflection).isTrue();
        }

        @Test
        @DisplayName("scenario12 · 持久化失败，不触发 Reflection，不抛异常")
        void scenario12_persistFails_reflectionNotTriggered() {
            when(memoryManager.add(anyString(), anyString(), anyString(), any(MemoryType.class), anyDouble()))
                    .thenThrow(new RuntimeException("PGVector down"));

            memoryConsolidator.onEpisodicMemory(
                    new EpisodicMemoryEvent("sess-fail", USER_ID, "some summary", 0.5));

            // 持久化失败 → early return → 不发 ReflectionEvent
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
        @DisplayName("scenario13 · Episodes 足够，LLM 提炼画像，写入 MemoryManager + UserProfile")
        void scenario13_sufficientEpisodes_profilePersistedEverywhere() {
            when(memoryManager.search(eq(USER_ID), anyString(), anyInt(), eq(MemoryType.EPISODIC)))
                    .thenReturn(makeEpisodeResults(EPISODE_THRESHOLD));
            when(callSpec.content()).thenReturn("用户是后端开发者，熟悉 Java/Go，关注系统性能。");

            reflectionWorker.onReflectionRequest(new ReflectionRequestEvent("sess-prof", USER_ID));

            // 写入 MemoryManager，type=PROCEDURAL，importance=0.9
            verify(memoryManager).add(eq(USER_ID), eq("sess-prof"),
                    eq("用户是后端开发者，熟悉 Java/Go，关注系统性能。"), eq(MemoryType.PROCEDURAL), eq(0.9));

            // 写入 UserProfile（Redis Hash）
            verify(hashOps).put(
                    argThat(k -> k.toString().contains(USER_ID)),
                    eq("reflection"),
                    eq("用户是后端开发者，熟悉 Java/Go，关注系统性能。")
            );
        }

        @Test
        @DisplayName("scenario14 · Episodes 不足（< threshold/2），跳过 Reflection")
        void scenario14_insufficientEpisodes_reflectionSkipped() {
            // 只有 1 个 episode，EPISODE_THRESHOLD=4 → threshold/2=2 → 不足
            when(memoryManager.search(eq(USER_ID), anyString(), anyInt(), eq(MemoryType.EPISODIC)))
                    .thenReturn(makeEpisodeResults(1));

            reflectionWorker.onReflectionRequest(new ReflectionRequestEvent("sess-skip", USER_ID));

            verify(chatClient, never()).prompt();
            verify(memoryManager, never()).add(anyString(), anyString(), anyString(), any(MemoryType.class), anyDouble());
        }

        @Test
        @DisplayName("scenario15 · LLM Reflection 失败，不写入 MemoryManager 也不更新 UserProfile")
        void scenario15_llmReflectionFails_noSideEffects() {
            when(memoryManager.search(eq(USER_ID), anyString(), anyInt(), eq(MemoryType.EPISODIC)))
                    .thenReturn(makeEpisodeResults(EPISODE_THRESHOLD));
            when(callSpec.content()).thenThrow(new RuntimeException("LLM error"));

            reflectionWorker.onReflectionRequest(new ReflectionRequestEvent("sess-llm-fail", USER_ID));

            verify(memoryManager, never()).add(anyString(), anyString(), anyString(), any(MemoryType.class), anyDouble());
            verify(hashOps, never()).put(any(), any(), any());
        }

        @Test
        @DisplayName("scenario16 · UserProfile.formatForSystemPrompt 将画像注入 System Prompt")
        void scenario16_formatForSystemPrompt_containsProfileAttributes() {
            when(hashOps.entries(anyString())).thenReturn(Map.of(
                    "reflection", "用户偏好 Go，关注高并发",
                    "language", "Go"
            ));

            String prompt = userProfileService.formatForSystemPrompt(USER_ID);

            assertThat(prompt).contains("用户画像");
            assertThat(prompt).contains("reflection");
            assertThat(prompt).contains("language");
        }

        @Test
        @DisplayName("scenario17 · UserProfile 为空时，formatForSystemPrompt 返回空字符串")
        void scenario17_emptyProfile_formatReturnsEmpty() {
            when(hashOps.entries(anyString())).thenReturn(Map.of());

            String prompt = userProfileService.formatForSystemPrompt("user-empty");

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
        @DisplayName("scenario18 · 低重要度 + 超龄记忆被删除")
        void scenario18_staleAndLowImportance_evicted() {
            MemoryEntity stale = staleEntity();
            when(memoryRepository.findEvictionCandidates(anyDouble(), any(Instant.class), any(Pageable.class)))
                    .thenReturn(List.of(stale));

            evictionPolicyManager.evict();

            verify(memoryManager).delete(stale.getId().toString());
        }

        @Test
        @DisplayName("scenario19 · 没有符合条件的候选时，不删除任何记忆")
        void scenario19_noCandidates_neverEvicted() {
            when(memoryRepository.findEvictionCandidates(anyDouble(), any(Instant.class), any(Pageable.class)))
                    .thenReturn(List.of());

            evictionPolicyManager.evict();

            verify(memoryManager, never()).delete(anyString());
        }

        @Test
        @DisplayName("scenario20 · 单条删除失败时，Eviction 静默继续不抛异常")
        void scenario20_deleteFailure_evictionContinuesSilently() {
            MemoryEntity stale = staleEntity();
            when(memoryRepository.findEvictionCandidates(anyDouble(), any(Instant.class), any(Pageable.class)))
                    .thenReturn(List.of(stale));
            when(memoryManager.delete(anyString())).thenThrow(new RuntimeException("delete failed"));

            // 不应抛出异常
            evictionPolicyManager.evict();

            verify(memoryManager).delete(stale.getId().toString());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  End-to-End 完整对话流程
    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("E2E · 完整 25 轮对话流程")
    class EndToEndConversationFlow {

        @Test
        @DisplayName("scenario21 · 25 轮对话驱动完整 L1→L2→L3 管道")
        void scenario21_fullConversation_entirePipelineFires() {
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
                    .thenReturn("用户是后端工程师，偏好 Go，关注架构设计和性能优化。");

            // L4：返回足够的 episodes（若触发 Reflection）
            when(memoryManager.search(eq(USER_ID), anyString(), anyInt(), eq(MemoryType.EPISODIC)))
                    .thenReturn(makeEpisodeResults(EPISODE_THRESHOLD));

            // ── Act：发送 25 轮消息（每轮 user + assistant = 2 条）──────────
            for (int turn = 1; turn <= 25; turn++) {
                memoryService.addMessage(sessionId, USER_ID, "user", "问题 " + turn);
                memoryService.addMessage(sessionId, USER_ID, "assistant", "回答 " + turn);
            }

            // ── Assert ────────────────────────────────────────────────────
            List<Object> events = pipelinePublisher.capturedEvents();

            long summarizationCount = events.stream()
                    .filter(e -> e instanceof SummarizationRequestEvent).count();
            assertThat(summarizationCount).isGreaterThanOrEqualTo(1);

            long episodicCount = events.stream()
                    .filter(e -> e instanceof EpisodicMemoryEvent).count();
            assertThat(episodicCount).isGreaterThanOrEqualTo(1);

            // MemoryManager 被写入（L3 情节记忆）
            verify(memoryManager, atLeastOnce())
                    .add(anyString(), anyString(), anyString(), any(MemoryType.class), anyDouble());
        }

        @Test
        @DisplayName("scenario22 · Redis 全程不可用，Fallback 保留最近 20 条消息")
        void scenario22_redisFullyDown_fallbackCapsAt20() {
            doThrow(new RuntimeException("Redis down")).when(listOps).rightPush(anyString(), any());
            doThrow(new RuntimeException("Redis down")).when(listOps).range(anyString(), anyLong(), anyLong());

            String sessionId = "sess-redis-down";
            // 发 25 条消息
            for (int i = 1; i <= 25; i++) {
                memoryService.addMessage(sessionId, USER_ID, "user", "msg " + i);
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

    private List<MemoryManager.MemorySearchResult> makeEpisodeResults(int count) {
        List<MemoryManager.MemorySearchResult> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            results.add(new MemoryManager.MemorySearchResult(
                    UUID.randomUUID().toString(),
                    "摘要 episode " + i + "：用户讨论了技术话题",
                    "episodic",
                    0.5,
                    0.9));
        }
        return results;
    }

    private MemoryEntity staleEntity() {
        MemoryEntity entity = new MemoryEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(USER_ID);
        entity.setContent("old chat");
        entity.setMemoryType(MemoryType.EPISODIC);
        entity.setImportance(0.05);
        Instant old = Instant.now().minus(200, ChronoUnit.DAYS);
        entity.setCreatedAt(old);
        entity.setUpdatedAt(old);
        entity.setLastAccessedAt(old);
        return entity;
    }

    /**
     * 同步路由事件总线，绕过 @Async，让测试在单线程内驱动完整管道。
     * 支持每个事件类型注册多个处理器，同时记录所有已发布的事件供断言使用。
     */
    static class RoutingEventPublisher implements ApplicationEventPublisher {

        private final Map<Class<?>, List<Consumer<Object>>> routes = new LinkedHashMap<>();
        private final List<Object> captured = new CopyOnWriteArrayList<>();

        @SuppressWarnings("unchecked")
        <T> void register(Class<T> eventType, Consumer<T> handler) {
            routes.computeIfAbsent(eventType, k -> new ArrayList<>())
                    .add((Consumer<Object>) handler);
        }

        @Override
        public void publishEvent(Object event) {
            captured.add(event);
            List<Consumer<Object>> handlers = routes.get(event.getClass());
            if (handlers != null) {
                handlers.forEach(h -> h.accept(event));
            }
        }

        List<Object> capturedEvents() {
            return Collections.unmodifiableList(captured);
        }
    }
}
