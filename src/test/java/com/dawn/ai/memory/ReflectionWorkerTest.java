package com.dawn.ai.memory;

import com.dawn.ai.memory.event.ReflectionRequestEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReflectionWorkerTest {

    private MemoryManager memoryManager;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private UserProfileService userProfileService;
    private ReflectionWorker reflectionWorker;

    @BeforeEach
    void setUp() {
        memoryManager = mock(MemoryManager.class);
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        userProfileService = mock(UserProfileService.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        // episodeThreshold=4, so needs >= 2 episodes to proceed
        reflectionWorker = new ReflectionWorker(memoryManager, chatClient, userProfileService, 4);
    }

    private static List<MemoryManager.MemorySearchResult> episodes(int count) {
        List<MemoryManager.MemorySearchResult> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new MemoryManager.MemorySearchResult(
                    UUID.randomUUID().toString(), "episode " + i, "episodic", 0.5, 0.9));
        }
        return list;
    }

    @Test
    void onReflectionRequest_persistsHighImportanceReflectionAsProcedural() {
        when(memoryManager.search(eq("user1"), anyString(), anyInt(), eq(MemoryType.EPISODIC)))
                .thenReturn(episodes(4));
        when(callSpec.content()).thenReturn("用户是Java开发者，擅长并发，正在学Spring。");

        reflectionWorker.onReflectionRequest(new ReflectionRequestEvent("session1", "user1"));

        verify(memoryManager).add(eq("user1"), eq("session1"),
                eq("用户是Java开发者，擅长并发，正在学Spring。"), eq(MemoryType.PROCEDURAL), eq(0.9));
        verify(userProfileService).upsertAttribute(eq("user1"), eq("reflection"),
                eq("用户是Java开发者，擅长并发，正在学Spring。"));
    }

    @Test
    void onReflectionRequest_skipsWhenNotEnoughEpisodes() {
        // episodeThreshold=4, threshold/2=2, only 1 episode → skip
        when(memoryManager.search(eq("user1"), anyString(), anyInt(), eq(MemoryType.EPISODIC)))
                .thenReturn(episodes(1));

        reflectionWorker.onReflectionRequest(new ReflectionRequestEvent("session1", "user1"));

        verify(chatClient, never()).prompt();
        verify(memoryManager, never()).add(anyString(), anyString(), anyString(), any(MemoryType.class), anyDouble());
    }

    @Test
    void onReflectionRequest_handlesLLMFailureGracefully() {
        when(memoryManager.search(eq("user1"), anyString(), anyInt(), eq(MemoryType.EPISODIC)))
                .thenReturn(episodes(4));
        when(callSpec.content()).thenThrow(new RuntimeException("LLM error"));

        reflectionWorker.onReflectionRequest(new ReflectionRequestEvent("session1", "user1"));

        verify(memoryManager, never()).add(anyString(), anyString(), anyString(), any(MemoryType.class), anyDouble());
        verify(userProfileService, never()).upsertAttribute(anyString(), anyString(), anyString());
    }
}
