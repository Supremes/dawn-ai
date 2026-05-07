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
    @SuppressWarnings("unchecked")
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
    void formatForSystemPrompt_containsProfileDataWhenNotEmpty() {
        when(hashOps.entries("ai:profile:user1")).thenReturn(Map.of("name", "Alice"));
        String result = profileService.formatForSystemPrompt("user1");
        assertThat(result).contains("用户画像").contains("name").contains("Alice");
    }
}
