package com.dawn.ai.service;

import com.dawn.ai.memory.SummarizationRequestEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${app.memory.summary.batch-size:5}")
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
