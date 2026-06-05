# Memory P0 Engineering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the 6 P0 memory engineering capabilities defined in `docs/memory/TODO.md`: Redis failsafe, Summary Buffer, Memory Consolidation, User Profile / Hard Memory, Decay/Eviction, and Reflection.

**Architecture:** Extend `MemoryService` with failsafe Redis operations and a summary buffer backed by application events; add `MemorySummarizer`, `MemoryConsolidator`, `UserProfileService`, `EvictionPolicyManager`, and `ReflectionWorker` as independent Spring services; inject user profile into system prompt via `AgentOrchestrator`. All async operations use `ApplicationEventPublisher` to avoid circular dependencies.

**Tech Stack:** Spring Boot, Spring AI (ChatClient, VectorStore/PGVector), Spring Data Redis (RedisTemplate), Micrometer, Lombok, JUnit 5 + Mockito

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `src/main/java/com/dawn/ai/DawnAiApplication.java` | Add `@EnableScheduling`, `@EnableAsync` |
| Modify | `src/main/java/com/dawn/ai/service/MemoryService.java` | Redis failsafe + pending-summary queue + event publish |
| Create | `src/main/java/com/dawn/ai/memory/SummarizationRequestEvent.java` | Application event carrying session + popped messages |
| Create | `src/main/java/com/dawn/ai/memory/SummaryResult.java` | Record: sessionId, text, importanceScore, createdAt |
| Create | `src/main/java/com/dawn/ai/memory/MemorySummarizer.java` | Async event listener; compresses messages via LLM |
| Create | `src/main/java/com/dawn/ai/memory/MemoryConsolidator.java` | Persists SummaryResult to VectorStore; triggers Reflection |
| Create | `src/main/java/com/dawn/ai/memory/UserProfileService.java` | Redis-hash hard memory; formats profile for system prompt |
| Create | `src/main/java/com/dawn/ai/memory/EvictionPolicyManager.java` | Scheduled: removes stale low-importance VectorStore docs |
| Create | `src/main/java/com/dawn/ai/memory/ReflectionWorker.java` | LLM-based pattern extraction → high-importance VectorStore entry |
| Modify | `src/main/java/com/dawn/ai/agent/orchestration/AgentOrchestrator.java` | Inject `UserProfileService`; prefix system prompt with profile |
| Modify | `src/main/resources/application.yml` | Add `app.memory.*` config keys |
| Create | `src/test/java/com/dawn/ai/service/MemoryServiceTest.java` | Unit: failsafe + summary buffer trigger |
| Create | `src/test/java/com/dawn/ai/memory/MemorySummarizerTest.java` | Unit: LLM summarization + fallback |
| Create | `src/test/java/com/dawn/ai/memory/MemoryConsolidatorTest.java` | Unit: VectorStore write + reflection trigger |
| Create | `src/test/java/com/dawn/ai/memory/UserProfileServiceTest.java` | Unit: profile read/write/format |
| Create | `src/test/java/com/dawn/ai/memory/EvictionPolicyManagerTest.java` | Unit: scoring + delete |
| Create | `src/test/java/com/dawn/ai/memory/ReflectionWorkerTest.java` | Unit: reflection generation |

---

## Task 1: Enable Scheduling & Async

**Files:**
- Modify: `src/main/java/com/dawn/ai/DawnAiApplication.java`

- [ ] **Step 1.1: Add annotations to DawnAiApplication**

```java
package com.dawn.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class DawnAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(DawnAiApplication.class, args);
    }
}
```

- [ ] **Step 1.2: Verify compilation**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 1.3: Commit**

```bash
git add src/main/java/com/dawn/ai/DawnAiApplication.java
git commit -m "feat(memory): enable scheduling and async for memory lifecycle"
```

---

## Task 2: Add memory config to application.yml

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 2.1: Append memory config block**

Add to the end of `application.yml`:

```yaml
  memory:
    summary:
      batch-size: 5          # Number of popped messages before triggering summarization
    consolidation:
      reflection-threshold: 10  # Min summaries before reflection runs
    eviction:
      cron: "0 0 3 * * ?"    # 3am daily
      importance-threshold: 0.1
      max-age-days: 180
    reflection:
      episode-threshold: 10
```

Note: this block goes under `app:`, so it becomes `app.memory.*`.

- [ ] **Step 2.2: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "feat(memory): add app.memory config keys"
```

---

## Task 3: Redis Failsafe in MemoryService

**Files:**
- Modify: `src/main/java/com/dawn/ai/service/MemoryService.java`
- Create: `src/main/java/com/dawn/ai/memory/SummarizationRequestEvent.java`
- Create: `src/test/java/com/dawn/ai/service/MemoryServiceTest.java`

- [ ] **Step 3.1: Create SummarizationRequestEvent**

```java
package com.dawn.ai.memory;

import java.util.List;
import java.util.Map;

public record SummarizationRequestEvent(String sessionId, List<Map<String, String>> messages) {}
```

- [ ] **Step 3.2: Write failing tests for MemoryService**

```java
package com.dawn.ai.service;

import com.dawn.ai.memory.SummarizationRequestEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryServiceTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ListOperations<String, Object> listOps;
    private ApplicationEventPublisher eventPublisher;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        listOps = mock(ListOperations.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        memoryService = new MemoryService(redisTemplate, new SimpleMeterRegistry(), eventPublisher);
    }

    @Test
    void addMessage_fallsBackToMemoryWhenRedisFails() {
        doThrow(new RuntimeException("Redis down")).when(listOps).rightPush(anyString(), any());

        memoryService.addMessage("session1", "user", "hello");
        memoryService.addMessage("session1", "assistant", "hi");

        // Redis down — should fall back silently
        // getHistory should return in-memory fallback
        doThrow(new RuntimeException("Redis down")).when(listOps).range(anyString(), anyLong(), anyLong());
        List<Map<String, String>> history = memoryService.getHistory("session1");

        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("role")).isEqualTo("user");
        assertThat(history.get(1).get("role")).isEqualTo("assistant");
    }

    @Test
    void addMessage_publishesSummarizationEventWhenBatchFull() {
        // Simulate: list size exceeds MAX_HISTORY (20), so leftPop fires
        when(listOps.rightPush(anyString(), any())).thenReturn(21L);
        when(listOps.size(anyString())).thenReturn(21L);
        Map<String, String> poppedMsg = Map.of("role", "user", "content", "old message");
        when(listOps.leftPop(anyString())).thenReturn(poppedMsg);

        // Pending list grows to SUMMARY_BATCH_SIZE (5) via repeated adds
        when(listOps.rightPush(contains(":pending"), any())).thenReturn(1L, 2L, 3L, 4L, 5L);
        when(listOps.size(contains(":pending"))).thenReturn(1L, 2L, 3L, 4L, 5L);

        // Trigger 5 pops to fill the batch
        for (int i = 0; i < 5; i++) {
            memoryService.addMessage("session1", "user", "msg " + i);
        }

        verify(eventPublisher, atLeastOnce()).publishEvent(any(SummarizationRequestEvent.class));
    }

    @Test
    void getHistory_returnsEmptyListWhenRedisAndFallbackBothEmpty() {
        when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(null);

        List<Map<String, String>> history = memoryService.getHistory("unknown-session");
        assertThat(history).isEmpty();
    }

    @Test
    void clearSession_removesSessionFromBothRedisAndFallback() {
        doThrow(new RuntimeException("Redis down")).when(listOps).rightPush(anyString(), any());
        memoryService.addMessage("session1", "user", "hello");

        memoryService.clearSession("session1");

        doThrow(new RuntimeException("Redis down")).when(listOps).range(anyString(), anyLong(), anyLong());
        List<Map<String, String>> history = memoryService.getHistory("session1");
        assertThat(history).isEmpty();
    }
}
```

- [ ] **Step 3.3: Run tests to confirm they fail**

```bash
./mvnw test -pl . -Dtest=MemoryServiceTest -q 2>&1 | tail -20
```

Expected: FAILURE (class not yet updated)

- [ ] **Step 3.4: Rewrite MemoryService with failsafe + summary buffer**

```java
package com.dawn.ai.service;

import com.dawn.ai.memory.SummarizationRequestEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class MemoryService {

    private static final String SESSION_PREFIX = "ai:session:";
    private static final String PENDING_SUFFIX = ":pending";
    private static final int MAX_HISTORY = 20;
    private static final Duration SESSION_TTL = Duration.ofHours(2);

    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;

    private final ConcurrentHashMap<String, List<Map<String, String>>> fallbackStore = new ConcurrentHashMap<>();

    private Counter redisWriteFailureCounter;
    private Counter redisReadFailureCounter;

    @org.springframework.beans.factory.annotation.Value("${app.memory.summary.batch-size:5}")
    private int summaryBatchSize;

    public MemoryService(RedisTemplate<String, Object> redisTemplate,
                         MeterRegistry meterRegistry,
                         ApplicationEventPublisher eventPublisher) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    void initMetrics() {
        redisWriteFailureCounter = Counter.builder("agent.memory.redis.failure")
                .tag("operation", "write").register(meterRegistry);
        redisReadFailureCounter = Counter.builder("agent.memory.redis.failure")
                .tag("operation", "read").register(meterRegistry);
    }

    public String createSession() {
        return UUID.randomUUID().toString();
    }

    @SuppressWarnings("unchecked")
    public void addMessage(String sessionId, String role, String content) {
        String key = SESSION_PREFIX + sessionId;
        Map<String, String> message = Map.of("role", role, "content", content);
        try {
            redisTemplate.opsForList().rightPush(key, message);
            Long size = redisTemplate.opsForList().size(key);
            if (size != null && size > MAX_HISTORY) {
                Object popped = redisTemplate.opsForList().leftPop(key);
                if (popped instanceof Map<?, ?> poppedMsg) {
                    enqueuePending(sessionId, (Map<String, String>) poppedMsg);
                }
            }
            redisTemplate.expire(key, SESSION_TTL);
        } catch (Exception e) {
            log.warn("[MemoryService] Redis write failed session={}: {}", sessionId, e.getMessage());
            redisWriteFailureCounter.increment();
            writeFallback(sessionId, message);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getHistory(String sessionId) {
        String key = SESSION_PREFIX + sessionId;
        try {
            List<Object> raw = redisTemplate.opsForList().range(key, 0, -1);
            if (raw == null) return new ArrayList<>();
            return raw.stream()
                    .filter(o -> o instanceof Map)
                    .map(o -> (Map<String, String>) o)
                    .toList();
        } catch (Exception e) {
            log.warn("[MemoryService] Redis read failed session={}, using fallback: {}", sessionId, e.getMessage());
            redisReadFailureCounter.increment();
            return new ArrayList<>(fallbackStore.getOrDefault(sessionId, List.of()));
        }
    }

    public void clearSession(String sessionId) {
        try {
            redisTemplate.delete(SESSION_PREFIX + sessionId);
            redisTemplate.delete(SESSION_PREFIX + sessionId + PENDING_SUFFIX);
        } catch (Exception e) {
            log.warn("[MemoryService] Redis delete failed session={}: {}", sessionId, e.getMessage());
        }
        fallbackStore.remove(sessionId);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> drainPending(String sessionId) {
        String pendingKey = SESSION_PREFIX + sessionId + PENDING_SUFFIX;
        try {
            List<Object> raw = redisTemplate.opsForList().range(pendingKey, 0, -1);
            redisTemplate.delete(pendingKey);
            if (raw == null) return List.of();
            return raw.stream()
                    .filter(o -> o instanceof Map)
                    .map(o -> (Map<String, String>) o)
                    .toList();
        } catch (Exception e) {
            log.warn("[MemoryService] Failed to drain pending for session={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private void enqueuePending(String sessionId, Map<String, String> message) {
        String pendingKey = SESSION_PREFIX + sessionId + PENDING_SUFFIX;
        try {
            redisTemplate.opsForList().rightPush(pendingKey, message);
            Long pendingSize = redisTemplate.opsForList().size(pendingKey);
            redisTemplate.expire(pendingKey, SESSION_TTL);
            if (pendingSize != null && pendingSize >= summaryBatchSize) {
                List<Map<String, String>> batch = drainPending(sessionId);
                if (!batch.isEmpty()) {
                    eventPublisher.publishEvent(new SummarizationRequestEvent(sessionId, batch));
                }
            }
        } catch (Exception e) {
            log.warn("[MemoryService] Failed to enqueue pending for session={}: {}", sessionId, e.getMessage());
        }
    }

    private void writeFallback(String sessionId, Map<String, String> message) {
        List<Map<String, String>> list = fallbackStore.computeIfAbsent(sessionId, k -> new ArrayList<>());
        synchronized (list) {
            list.add(message);
            if (list.size() > MAX_HISTORY) list.remove(0);
        }
    }
}
```

- [ ] **Step 3.5: Run tests**

```bash
./mvnw test -pl . -Dtest=MemoryServiceTest -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS, 4 tests pass

- [ ] **Step 3.6: Commit**

```bash
git add src/main/java/com/dawn/ai/service/MemoryService.java \
        src/main/java/com/dawn/ai/memory/SummarizationRequestEvent.java \
        src/test/java/com/dawn/ai/service/MemoryServiceTest.java
git commit -m "feat(memory): redis failsafe + summary buffer event trigger"
```

---

## Task 4: Summary Buffer — MemorySummarizer

**Files:**
- Create: `src/main/java/com/dawn/ai/memory/SummaryResult.java`
- Create: `src/main/java/com/dawn/ai/memory/MemorySummarizer.java`
- Create: `src/test/java/com/dawn/ai/memory/MemorySummarizerTest.java`

- [ ] **Step 4.1: Create SummaryResult record**

```java
package com.dawn.ai.memory;

import java.time.Instant;

public record SummaryResult(String sessionId, String text, double importanceScore, Instant createdAt) {}
```

- [ ] **Step 4.2: Write failing tests for MemorySummarizer**

```java
package com.dawn.ai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemorySummarizerTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ApplicationEventPublisher eventPublisher;
    private MemorySummarizer summarizer;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        summarizer = new MemorySummarizer(chatClient, eventPublisher);
    }

    @Test
    void onSummarizationRequest_publishesConsolidationEvent() {
        when(callSpec.content()).thenReturn("用户讨论了天气问题，询问了北京气温。");

        SummarizationRequestEvent event = new SummarizationRequestEvent(
                "session1",
                List.of(
                        Map.of("role", "user", "content", "北京今天天气如何？"),
                        Map.of("role", "assistant", "content", "北京今天晴，25度。")
                )
        );

        summarizer.onSummarizationRequest(event);

        verify(eventPublisher).publishEvent(argThat(e ->
                e instanceof ConsolidationRequestEvent cre &&
                "session1".equals(cre.result().sessionId()) &&
                cre.result().text().contains("天气")
        ));
    }

    @Test
    void onSummarizationRequest_usesRawTextFallbackWhenLLMFails() {
        when(callSpec.content()).thenThrow(new RuntimeException("LLM timeout"));

        SummarizationRequestEvent event = new SummarizationRequestEvent(
                "session2",
                List.of(Map.of("role", "user", "content", "test message"))
        );

        summarizer.onSummarizationRequest(event);

        // Should still publish with fallback text
        verify(eventPublisher).publishEvent(argThat(e ->
                e instanceof ConsolidationRequestEvent cre &&
                cre.result().importanceScore() < 0.4
        ));
    }
}
```

- [ ] **Step 4.3: Run tests to confirm they fail**

```bash
./mvnw test -pl . -Dtest=MemorySummarizerTest -q 2>&1 | tail -10
```

Expected: FAILURE (class not found)

- [ ] **Step 4.4: Create ConsolidationRequestEvent**

```java
package com.dawn.ai.memory;

public record ConsolidationRequestEvent(SummaryResult result) {}
```

- [ ] **Step 4.5: Create MemorySummarizer**

```java
package com.dawn.ai.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemorySummarizer {

    private final ChatClient chatClient;
    private final ApplicationEventPublisher eventPublisher;

    private static final String PROMPT_TEMPLATE =
            "以下是一段对话历史，请将其压缩成简洁的摘要（100字以内），保留关键信息、用户偏好和重要事实。\n" +
            "对话历史:\n%s\n摘要:";

    @EventListener
    @Async
    public void onSummarizationRequest(SummarizationRequestEvent event) {
        String historyText = event.messages().stream()
                .map(m -> m.get("role") + ": " + m.get("content"))
                .collect(Collectors.joining("\n"));

        SummaryResult result;
        try {
            String summary = chatClient.prompt()
                    .user(PROMPT_TEMPLATE.formatted(historyText))
                    .call()
                    .content();
            result = new SummaryResult(event.sessionId(), summary, 0.5, Instant.now());
            log.info("[MemorySummarizer] Summarized {} messages for session={}", event.messages().size(), event.sessionId());
        } catch (Exception e) {
            log.warn("[MemorySummarizer] LLM failed for session={}, using raw fallback: {}", event.sessionId(), e.getMessage());
            result = new SummaryResult(event.sessionId(), historyText, 0.3, Instant.now());
        }
        eventPublisher.publishEvent(new ConsolidationRequestEvent(result));
    }
}
```

- [ ] **Step 4.6: Run tests**

```bash
./mvnw test -pl . -Dtest=MemorySummarizerTest -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 2 tests pass

- [ ] **Step 4.7: Commit**

```bash
git add src/main/java/com/dawn/ai/memory/SummaryResult.java \
        src/main/java/com/dawn/ai/memory/MemorySummarizer.java \
        src/main/java/com/dawn/ai/memory/ConsolidationRequestEvent.java \
        src/test/java/com/dawn/ai/memory/MemorySummarizerTest.java
git commit -m "feat(memory): summary buffer - async LLM compression of evicted messages"
```

---

## Task 5: Memory Consolidation — persist summaries to VectorStore

**Files:**
- Create: `src/main/java/com/dawn/ai/memory/MemoryConsolidator.java`
- Create: `src/test/java/com/dawn/ai/memory/MemoryConsolidatorTest.java`

- [ ] **Step 5.1: Write failing tests for MemoryConsolidator**

```java
package com.dawn.ai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class MemoryConsolidatorTest {

    private VectorStore vectorStore;
    private ApplicationEventPublisher eventPublisher;
    private MemoryConsolidator consolidator;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        consolidator = new MemoryConsolidator(vectorStore, eventPublisher, 3);
    }

    @Test
    void onConsolidationRequest_writesDocumentToVectorStore() {
        SummaryResult summary = new SummaryResult("s1", "User prefers Python.", 0.5, Instant.now());
        consolidator.onConsolidationRequest(new ConsolidationRequestEvent(summary));

        verify(vectorStore).add(argThat(docs ->
                docs.size() == 1 &&
                docs.get(0).getText().equals("User prefers Python.") &&
                "summary".equals(docs.get(0).getMetadata().get("type")) &&
                "s1".equals(docs.get(0).getMetadata().get("sessionId"))
        ));
    }

    @Test
    void onConsolidationRequest_publishesReflectionEventWhenThresholdReached() {
        consolidator = new MemoryConsolidator(vectorStore, eventPublisher, 2);

        SummaryResult s1 = new SummaryResult("s1", "Summary A", 0.5, Instant.now());
        SummaryResult s2 = new SummaryResult("s1", "Summary B", 0.5, Instant.now());

        consolidator.onConsolidationRequest(new ConsolidationRequestEvent(s1));
        consolidator.onConsolidationRequest(new ConsolidationRequestEvent(s2));

        verify(eventPublisher).publishEvent(any(ReflectionRequestEvent.class));
    }

    @Test
    void onConsolidationRequest_stillSucceedsWhenVectorStoreFails() {
        doThrow(new RuntimeException("PGVector down")).when(vectorStore).add(anyList());

        SummaryResult summary = new SummaryResult("s1", "Some summary", 0.5, Instant.now());
        // Should not throw
        consolidator.onConsolidationRequest(new ConsolidationRequestEvent(summary));
    }
}
```

- [ ] **Step 5.2: Run tests to confirm they fail**

```bash
./mvnw test -pl . -Dtest=MemoryConsolidatorTest -q 2>&1 | tail -10
```

Expected: FAILURE

- [ ] **Step 5.3: Create ReflectionRequestEvent**

```java
package com.dawn.ai.memory;

public record ReflectionRequestEvent(String sessionId) {}
```

- [ ] **Step 5.4: Create MemoryConsolidator**

```java
package com.dawn.ai.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class MemoryConsolidator {

    private final VectorStore vectorStore;
    private final ApplicationEventPublisher eventPublisher;
    private final int reflectionThreshold;

    private final ConcurrentHashMap<String, AtomicInteger> consolidationCount = new ConcurrentHashMap<>();

    public MemoryConsolidator(VectorStore vectorStore,
                               ApplicationEventPublisher eventPublisher,
                               @Value("${app.memory.consolidation.reflection-threshold:10}") int reflectionThreshold) {
        this.vectorStore = vectorStore;
        this.eventPublisher = eventPublisher;
        this.reflectionThreshold = reflectionThreshold;
    }

    @EventListener
    @Async
    public void onConsolidationRequest(ConsolidationRequestEvent event) {
        SummaryResult result = event.result();
        Document doc = new Document(
                UUID.randomUUID().toString(),
                result.text(),
                Map.of(
                        "type", "summary",
                        "sessionId", result.sessionId(),
                        "importance", result.importanceScore(),
                        "createdAt", result.createdAt().toEpochMilli(),
                        "lastAccessedAt", result.createdAt().toEpochMilli()
                )
        );
        try {
            vectorStore.add(List.of(doc));
            log.info("[MemoryConsolidator] Persisted summary for session={}, importance={}", result.sessionId(), result.importanceScore());
        } catch (Exception e) {
            log.warn("[MemoryConsolidator] VectorStore write failed session={}: {}", result.sessionId(), e.getMessage());
            return;
        }

        int count = consolidationCount
                .computeIfAbsent(result.sessionId(), k -> new AtomicInteger())
                .incrementAndGet();
        if (count >= reflectionThreshold) {
            consolidationCount.get(result.sessionId()).set(0);
            eventPublisher.publishEvent(new ReflectionRequestEvent(result.sessionId()));
        }
    }
}
```

- [ ] **Step 5.5: Run tests**

```bash
./mvnw test -pl . -Dtest=MemoryConsolidatorTest -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 3 tests pass

- [ ] **Step 5.6: Commit**

```bash
git add src/main/java/com/dawn/ai/memory/MemoryConsolidator.java \
        src/main/java/com/dawn/ai/memory/ReflectionRequestEvent.java \
        src/test/java/com/dawn/ai/memory/MemoryConsolidatorTest.java
git commit -m "feat(memory): consolidation - persist summaries to VectorStore with reflection trigger"
```

---

## Task 6: User Profile / Hard Memory

**Files:**
- Create: `src/main/java/com/dawn/ai/memory/UserProfileService.java`
- Modify: `src/main/java/com/dawn/ai/agent/orchestration/AgentOrchestrator.java`
- Create: `src/test/java/com/dawn/ai/memory/UserProfileServiceTest.java`

- [ ] **Step 6.1: Write failing tests for UserProfileService**

```java
package com.dawn.ai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserProfileServiceTest {

    private RedisTemplate<String, Object> redisTemplate;
    private HashOperations<String, Object, Object> hashOps;
    private UserProfileService profileService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        profileService = new UserProfileService(redisTemplate);
    }

    @Test
    void upsertAttribute_writesToRedisHash() {
        profileService.upsertAttribute("user1", "language", "Java");
        verify(hashOps).put("ai:profile:user1", "language", "Java");
    }

    @Test
    void getProfile_returnsStringMap() {
        when(hashOps.entries("ai:profile:user1")).thenReturn(Map.of("language", "Java", "level", "senior"));

        Map<String, String> profile = profileService.getProfile("user1");

        assertThat(profile).containsEntry("language", "Java").containsEntry("level", "senior");
    }

    @Test
    void formatForSystemPrompt_returnsEmptyStringWhenProfileEmpty() {
        when(hashOps.entries(anyString())).thenReturn(Map.of());
        assertThat(profileService.formatForSystemPrompt("user1")).isEmpty();
    }

    @Test
    void formatForSystemPrompt_returnsFormattedSectionWhenProfileNotEmpty() {
        when(hashOps.entries("ai:profile:user1")).thenReturn(Map.of("name", "Alice"));
        String result = profileService.formatForSystemPrompt("user1");
        assertThat(result).contains("用户画像").contains("name").contains("Alice");
    }
}
```

- [ ] **Step 6.2: Run tests to confirm they fail**

```bash
./mvnw test -pl . -Dtest=UserProfileServiceTest -q 2>&1 | tail -10
```

Expected: FAILURE

- [ ] **Step 6.3: Create UserProfileService**

```java
package com.dawn.ai.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String PROFILE_PREFIX = "ai:profile:";
    private static final Duration PROFILE_TTL = Duration.ofDays(30);

    public void upsertAttribute(String userId, String key, String value) {
        String profileKey = PROFILE_PREFIX + userId;
        try {
            redisTemplate.opsForHash().put(profileKey, key, value);
            redisTemplate.expire(profileKey, PROFILE_TTL);
        } catch (Exception e) {
            log.warn("[UserProfileService] Failed to upsert profile userId={}: {}", userId, e.getMessage());
        }
    }

    public Map<String, String> getProfile(String userId) {
        String profileKey = PROFILE_PREFIX + userId;
        try {
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(profileKey);
            return raw.entrySet().stream().collect(Collectors.toMap(
                    e -> String.valueOf(e.getKey()),
                    e -> String.valueOf(e.getValue())
            ));
        } catch (Exception e) {
            log.warn("[UserProfileService] Failed to read profile userId={}: {}", userId, e.getMessage());
            return Map.of();
        }
    }

    public String formatForSystemPrompt(String userId) {
        Map<String, String> profile = getProfile(userId);
        if (profile.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n【用户画像】\n");
        profile.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
        return sb.toString();
    }
}
```

- [ ] **Step 6.4: Run tests**

```bash
./mvnw test -pl . -Dtest=UserProfileServiceTest -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 4 tests pass

- [ ] **Step 6.5: Inject UserProfileService into AgentOrchestrator**

In `src/main/java/com/dawn/ai/agent/orchestration/AgentOrchestrator.java`, make these changes:

Add field after existing fields:
```java
private final UserProfileService userProfileService;
```

Update constructor (add parameter after `MeterRegistry meterRegistry`):
```java
public AgentOrchestrator(ChatClient chatClient,
                          MemoryService memoryService,
                          TaskPlanner taskPlanner,
                          ToolRegistry toolRegistry,
                          MeterRegistry meterRegistry,
                          UserProfileService userProfileService) {
    this.chatClient = chatClient;
    this.memoryService = memoryService;
    this.taskPlanner = taskPlanner;
    this.toolRegistry = toolRegistry;
    this.meterRegistry = meterRegistry;
    this.userProfileService = userProfileService;
}
```

Modify `buildSystemPrompt` to prepend profile (sessionId doubles as userId until auth is added):
```java
private String buildSystemPrompt(List<PlanStep> plan, String sessionId) {
    String profileSection = userProfileService.formatForSystemPrompt(sessionId);
    return baseSystemPrompt
            + profileSection
            + formatPlan(plan)
            + formatPlanEnforcement(plan)
            + String.format("%n请在回复中简短说明每次工具调用的原因。最多调用工具 %d 次。", maxSteps);
}
```

Update all callers of `buildSystemPrompt(plan)` to pass `sessionId`:

In `doChat`:
```java
String systemPrompt = buildSystemPrompt(plan, sessionId);
```

In `streamChat`:
```java
String systemPrompt = buildSystemPrompt(plan, sessionId);
```

Remove `@RequiredArgsConstructor` since we now have an explicit constructor. The class currently has `@RequiredArgsConstructor` — remove it and keep the explicit constructor above.

- [ ] **Step 6.6: Compile check**

```bash
./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 6.7: Commit**

```bash
git add src/main/java/com/dawn/ai/memory/UserProfileService.java \
        src/main/java/com/dawn/ai/agent/orchestration/AgentOrchestrator.java \
        src/test/java/com/dawn/ai/memory/UserProfileServiceTest.java
git commit -m "feat(memory): user profile / hard memory injected into system prompt"
```

---

## Task 7: Decay / Eviction

**Files:**
- Create: `src/main/java/com/dawn/ai/memory/EvictionPolicyManager.java`
- Create: `src/test/java/com/dawn/ai/memory/EvictionPolicyManagerTest.java`

- [ ] **Step 7.1: Write failing tests for EvictionPolicyManager**

```java
package com.dawn.ai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class EvictionPolicyManagerTest {

    private VectorStore vectorStore;
    private EvictionPolicyManager manager;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        manager = new EvictionPolicyManager(vectorStore, 0.1, 180);
    }

    @Test
    void evict_deletesLowImportanceOldDocuments() {
        long oldTs = Instant.now().minus(200, ChronoUnit.DAYS).toEpochMilli();
        Document stale = new Document("doc1", "old content",
                Map.of("type", "summary", "importance", 0.05, "createdAt", oldTs));
        when(vectorStore.similaritySearch(any())).thenReturn(List.of(stale));

        manager.evict();

        verify(vectorStore).delete(argThat(ids -> ids.contains("doc1")));
    }

    @Test
    void evict_keepsHighImportanceDocumentsEvenIfOld() {
        long oldTs = Instant.now().minus(200, ChronoUnit.DAYS).toEpochMilli();
        Document important = new Document("doc2", "important content",
                Map.of("type", "summary", "importance", 0.9, "createdAt", oldTs));
        when(vectorStore.similaritySearch(any())).thenReturn(List.of(important));

        manager.evict();

        verify(vectorStore, never()).delete(any());
    }

    @Test
    void evict_keepsRecentDocumentsEvenIfLowImportance() {
        long recentTs = Instant.now().minus(10, ChronoUnit.DAYS).toEpochMilli();
        Document recent = new Document("doc3", "recent content",
                Map.of("type", "summary", "importance", 0.05, "createdAt", recentTs));
        when(vectorStore.similaritySearch(any())).thenReturn(List.of(recent));

        manager.evict();

        verify(vectorStore, never()).delete(any());
    }

    @Test
    void evict_handlesVectorStoreFailureGracefully() {
        when(vectorStore.similaritySearch(any())).thenThrow(new RuntimeException("DB down"));
        // Should not throw
        manager.evict();
        verify(vectorStore, never()).delete(any());
    }
}
```

- [ ] **Step 7.2: Run tests to confirm they fail**

```bash
./mvnw test -pl . -Dtest=EvictionPolicyManagerTest -q 2>&1 | tail -10
```

Expected: FAILURE

- [ ] **Step 7.3: Create EvictionPolicyManager**

```java
package com.dawn.ai.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
public class EvictionPolicyManager {

    private final VectorStore vectorStore;
    private final double importanceThreshold;
    private final int maxAgeDays;

    private static final String EVICTION_PROBE_QUERY = "对话历史摘要";
    private static final int EVICTION_BATCH = 100;

    public EvictionPolicyManager(
            VectorStore vectorStore,
            @Value("${app.memory.eviction.importance-threshold:0.1}") double importanceThreshold,
            @Value("${app.memory.eviction.max-age-days:180}") int maxAgeDays) {
        this.vectorStore = vectorStore;
        this.importanceThreshold = importanceThreshold;
        this.maxAgeDays = maxAgeDays;
    }

    @Scheduled(cron = "${app.memory.eviction.cron:0 0 3 * * ?}")
    public void evict() {
        long cutoffMs = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS).toEpochMilli();
        List<Document> candidates;
        try {
            candidates = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(EVICTION_PROBE_QUERY)
                            .topK(EVICTION_BATCH)
                            .similarityThreshold(0.0)
                            .build());
        } catch (Exception e) {
            log.warn("[EvictionPolicyManager] Failed to fetch eviction candidates: {}", e.getMessage());
            return;
        }

        List<String> toDelete = candidates.stream()
                .filter(doc -> isStale(doc, cutoffMs))
                .map(Document::getId)
                .toList();

        if (toDelete.isEmpty()) {
            log.debug("[EvictionPolicyManager] No documents to evict");
            return;
        }
        vectorStore.delete(toDelete);
        log.info("[EvictionPolicyManager] Evicted {} documents (importance<{}, age>{}d)", toDelete.size(), importanceThreshold, maxAgeDays);
    }

    private boolean isStale(Document doc, long cutoffMs) {
        Object imp = doc.getMetadata().get("importance");
        Object ts = doc.getMetadata().get("createdAt");
        double importance = imp instanceof Number n ? n.doubleValue() : 1.0;
        long createdAt = ts instanceof Number n ? n.longValue() : Long.MAX_VALUE;
        return importance < importanceThreshold && createdAt < cutoffMs;
    }
}
```

- [ ] **Step 7.4: Run tests**

```bash
./mvnw test -pl . -Dtest=EvictionPolicyManagerTest -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 4 tests pass

- [ ] **Step 7.5: Commit**

```bash
git add src/main/java/com/dawn/ai/memory/EvictionPolicyManager.java \
        src/test/java/com/dawn/ai/memory/EvictionPolicyManagerTest.java
git commit -m "feat(memory): decay/eviction - scheduled removal of stale low-importance summaries"
```

---

## Task 8: Reflection

**Files:**
- Create: `src/main/java/com/dawn/ai/memory/ReflectionWorker.java`
- Create: `src/test/java/com/dawn/ai/memory/ReflectionWorkerTest.java`

- [ ] **Step 8.1: Write failing tests for ReflectionWorker**

```java
package com.dawn.ai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReflectionWorkerTest {

    private VectorStore vectorStore;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ReflectionWorker reflectionWorker;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        reflectionWorker = new ReflectionWorker(vectorStore, chatClient, 3);
    }

    @Test
    void onReflectionRequest_persistsHighImportanceReflectionToVectorStore() {
        List<Document> episodes = List.of(
                new Document("1", "用户喜欢Java", Map.of()),
                new Document("2", "用户偏好并发编程", Map.of()),
                new Document("3", "用户在学习Spring", Map.of())
        );
        when(vectorStore.similaritySearch(any())).thenReturn(episodes);
        when(callSpec.content()).thenReturn("用户是Java开发者，擅长并发，正在学Spring。");

        reflectionWorker.onReflectionRequest(new ReflectionRequestEvent("session1"));

        verify(vectorStore).add(argThat(docs ->
                docs.size() == 1 &&
                docs.get(0).getMetadata().get("type").equals("reflection") &&
                ((Number) docs.get(0).getMetadata().get("importance")).doubleValue() >= 0.8
        ));
    }

    @Test
    void onReflectionRequest_skipsWhenNotEnoughEpisodes() {
        when(vectorStore.similaritySearch(any())).thenReturn(List.of(
                new Document("1", "only one episode", Map.of())
        ));

        reflectionWorker.onReflectionRequest(new ReflectionRequestEvent("session1"));

        verify(chatClient, never()).prompt();
        verify(vectorStore, never()).add(any());
    }

    @Test
    void onReflectionRequest_handlesLLMFailureGracefully() {
        List<Document> episodes = List.of(
                new Document("1", "e1", Map.of()),
                new Document("2", "e2", Map.of()),
                new Document("3", "e3", Map.of())
        );
        when(vectorStore.similaritySearch(any())).thenReturn(episodes);
        when(callSpec.content()).thenThrow(new RuntimeException("LLM error"));

        // Should not throw
        reflectionWorker.onReflectionRequest(new ReflectionRequestEvent("session1"));
        verify(vectorStore, never()).add(any());
    }
}
```

- [ ] **Step 8.2: Run tests to confirm they fail**

```bash
./mvnw test -pl . -Dtest=ReflectionWorkerTest -q 2>&1 | tail -10
```

Expected: FAILURE

- [ ] **Step 8.3: Create ReflectionWorker**

```java
package com.dawn.ai.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReflectionWorker {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final int episodeThreshold;

    private static final String REFLECT_PROMPT =
            "以下是用户的多段对话摘要，请从中提炼出用户的长期偏好、习惯和重要特征（200字以内）。\n" +
            "摘要集合:\n%s\n用户画像提炼:";

    public ReflectionWorker(
            VectorStore vectorStore,
            ChatClient chatClient,
            @Value("${app.memory.reflection.episode-threshold:10}") int episodeThreshold) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
        this.episodeThreshold = episodeThreshold;
    }

    @EventListener
    @Async
    public void onReflectionRequest(ReflectionRequestEvent event) {
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        List<Document> episodes;
        try {
            episodes = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("用户偏好和习惯")
                            .topK(episodeThreshold)
                            .filterExpression(fb.eq("sessionId", event.sessionId()).build())
                            .build());
        } catch (Exception e) {
            log.warn("[ReflectionWorker] VectorStore query failed session={}: {}", event.sessionId(), e.getMessage());
            return;
        }

        if (episodes.size() < episodeThreshold / 2) {
            log.debug("[ReflectionWorker] Not enough episodes ({}) for session={}", episodes.size(), event.sessionId());
            return;
        }

        String episodesText = episodes.stream().map(Document::getText).collect(Collectors.joining("\n---\n"));
        String reflection;
        try {
            reflection = chatClient.prompt()
                    .user(REFLECT_PROMPT.formatted(episodesText))
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("[ReflectionWorker] LLM reflection failed session={}: {}", event.sessionId(), e.getMessage());
            return;
        }

        Document reflectionDoc = new Document(
                UUID.randomUUID().toString(),
                reflection,
                Map.of(
                        "type", "reflection",
                        "sessionId", event.sessionId(),
                        "importance", 0.9,
                        "createdAt", Instant.now().toEpochMilli(),
                        "lastAccessedAt", Instant.now().toEpochMilli()
                )
        );
        try {
            vectorStore.add(List.of(reflectionDoc));
            log.info("[ReflectionWorker] Reflection persisted for session={}", event.sessionId());
        } catch (Exception e) {
            log.warn("[ReflectionWorker] VectorStore write failed session={}: {}", event.sessionId(), e.getMessage());
        }
    }
}
```

- [ ] **Step 8.4: Run tests**

```bash
./mvnw test -pl . -Dtest=ReflectionWorkerTest -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, 3 tests pass

- [ ] **Step 8.5: Commit**

```bash
git add src/main/java/com/dawn/ai/memory/ReflectionWorker.java \
        src/test/java/com/dawn/ai/memory/ReflectionWorkerTest.java
git commit -m "feat(memory): reflection - LLM-based pattern extraction to high-importance VectorStore entry"
```

---

## Task 9: Full test suite + architecture guard

- [ ] **Step 9.1: Run all tests**

```bash
./mvnw test -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 9.2: Verify memory package structure**

```bash
find src/main/java/com/dawn/ai/memory -name "*.java" | sort
```

Expected output (7 files):
```
src/main/java/com/dawn/ai/memory/ConsolidationRequestEvent.java
src/main/java/com/dawn/ai/memory/EvictionPolicyManager.java
src/main/java/com/dawn/ai/memory/MemoryConsolidator.java
src/main/java/com/dawn/ai/memory/MemorySummarizer.java
src/main/java/com/dawn/ai/memory/ReflectionRequestEvent.java
src/main/java/com/dawn/ai/memory/ReflectionWorker.java
src/main/java/com/dawn/ai/memory/SummarizationRequestEvent.java
src/main/java/com/dawn/ai/memory/SummaryResult.java
src/main/java/com/dawn/ai/memory/UserProfileService.java
```

- [ ] **Step 9.3: Final commit**

```bash
git add .
git commit -m "feat(memory): complete P0 memory engineering - failsafe, summary buffer, consolidation, user profile, eviction, reflection"
```

---

## Self-Review

### Spec coverage

| P0 Task | Covered? | Task |
|---------|----------|------|
| Summary Buffer | ✅ | Task 3 (MemoryService) + Task 4 (MemorySummarizer) |
| Memory Consolidation | ✅ | Task 5 (MemoryConsolidator) |
| Reflection | ✅ | Task 8 (ReflectionWorker) |
| Decay/Eviction | ✅ | Task 7 (EvictionPolicyManager) |
| User Profile / Hard Memory | ✅ | Task 6 (UserProfileService + AgentOrchestrator) |
| Redis failsafe | ✅ | Task 3 (MemoryService failsafe) |

### Placeholder check

No TBDs or TODOs in any code sample. All test assertions are concrete. All commands include expected output.

### Type consistency

- `SummarizationRequestEvent(String sessionId, List<Map<String, String>> messages)` — used in MemoryService (publish) and MemorySummarizer (listen) ✅
- `ConsolidationRequestEvent(SummaryResult result)` — used in MemorySummarizer (publish) and MemoryConsolidator (listen) ✅
- `ReflectionRequestEvent(String sessionId)` — used in MemoryConsolidator (publish) and ReflectionWorker (listen) ✅
- `SummaryResult(String sessionId, String text, double importanceScore, Instant createdAt)` — consistent across Summarizer, Consolidator, tests ✅
- `buildSystemPrompt(List<PlanStep> plan, String sessionId)` — both `doChat` and `streamChat` pass `sessionId` ✅
