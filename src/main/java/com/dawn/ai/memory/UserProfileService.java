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
