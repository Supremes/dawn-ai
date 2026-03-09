package com.dawn.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conversation memory service backed by Redis.
 *
 * Design analogy: This is conceptually similar to Redis's List data structure
 * where we maintain an ordered message history per session — just like a
 * circular buffer with TTL eviction, analogous to Redis's EXPIRE + LPUSH/LRANGE.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private static final String SESSION_PREFIX = "ai:session:";
    private static final int MAX_HISTORY = 20;
    private static final Duration SESSION_TTL = Duration.ofHours(2);

    private final RedisTemplate<String, Object> redisTemplate;

    public String createSession() {
        return UUID.randomUUID().toString();
    }

    public void addMessage(String sessionId, String role, String content) {
        String key = SESSION_PREFIX + sessionId;
        Map<String, String> message = Map.of("role", role, "content", content);
        redisTemplate.opsForList().rightPush(key, message);

        // Keep only the last MAX_HISTORY messages to bound memory usage
        Long size = redisTemplate.opsForList().size(key);
        if (size != null && size > MAX_HISTORY) {
            redisTemplate.opsForList().leftPop(key);
        }
        redisTemplate.expire(key, SESSION_TTL);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getHistory(String sessionId) {
        String key = SESSION_PREFIX + sessionId;
        List<Object> raw = redisTemplate.opsForList().range(key, 0, -1);
        if (raw == null) return new ArrayList<>();
        return raw.stream()
                .filter(o -> o instanceof Map)
                .map(o -> (Map<String, String>) o)
                .toList();
    }

    public void clearSession(String sessionId) {
        redisTemplate.delete(SESSION_PREFIX + sessionId);
    }
}
