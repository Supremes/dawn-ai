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
        memoryService.initMetrics();
    }

    @Test
    void addMessage_fallsBackToMemoryWhenRedisFails() {
        doThrow(new RuntimeException("Redis down")).when(listOps).rightPush(anyString(), any());

        memoryService.addMessage("session1", "user", "hello");
        memoryService.addMessage("session1", "assistant", "hi");

        doThrow(new RuntimeException("Redis down")).when(listOps).range(anyString(), anyLong(), anyLong());
        List<Map<String, String>> history = memoryService.getHistory("session1");

        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("role")).isEqualTo("user");
        assertThat(history.get(1).get("role")).isEqualTo("assistant");
    }

    @Test
    void addMessage_publishesSummarizationEventWhenBatchFull() {
        when(listOps.rightPush(argThat(k -> k != null && !k.contains(":pending")), any())).thenReturn(21L);
        when(listOps.size(argThat(k -> k != null && !k.contains(":pending")))).thenReturn(21L);
        Map<String, String> poppedMsg = Map.of("role", "user", "content", "old message");
        when(listOps.leftPop(anyString())).thenReturn(poppedMsg);

        when(listOps.rightPush(argThat(k -> k != null && k.contains(":pending")), any())).thenReturn(5L);
        when(listOps.size(argThat(k -> k != null && k.contains(":pending")))).thenReturn(5L);
        when(listOps.range(argThat(k -> k != null && k.contains(":pending")), anyLong(), anyLong()))
                .thenReturn(List.of(poppedMsg));

        memoryService.addMessage("session1", "user", "msg");

        verify(eventPublisher).publishEvent(any(SummarizationRequestEvent.class));
    }

    @Test
    void getHistory_returnsEmptyListWhenRedisReturnsNull() {
        when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(null);

        List<Map<String, String>> history = memoryService.getHistory("unknown-session");
        assertThat(history).isEmpty();
    }

    @Test
    void clearSession_removesSessionFromFallback() {
        doThrow(new RuntimeException("Redis down")).when(listOps).rightPush(anyString(), any());
        memoryService.addMessage("session1", "user", "hello");

        memoryService.clearSession("session1");

        doThrow(new RuntimeException("Redis down")).when(listOps).range(anyString(), anyLong(), anyLong());
        List<Map<String, String>> history = memoryService.getHistory("session1");
        assertThat(history).isEmpty();
    }
}
