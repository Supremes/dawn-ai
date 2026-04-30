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

        // episodeThreshold=4, so needs >= 2 episodes to proceed
        reflectionWorker = new ReflectionWorker(vectorStore, chatClient, 4);
    }

    @Test
    void onReflectionRequest_persistsHighImportanceReflectionToVectorStore() {
        List<Document> episodes = List.of(
                new Document("1", "用户喜欢Java", Map.of()),
                new Document("2", "用户偏好并发编程", Map.of()),
                new Document("3", "用户在学习Spring", Map.of()),
                new Document("4", "用户关注性能优化", Map.of())
        );
        when(vectorStore.similaritySearch(any())).thenReturn(episodes);
        when(callSpec.content()).thenReturn("用户是Java开发者，擅长并发，正在学Spring。");

        reflectionWorker.onReflectionRequest(new ReflectionRequestEvent("session1"));

        verify(vectorStore).add(argThat(docs ->
                docs.size() == 1 &&
                "reflection".equals(docs.get(0).getMetadata().get("type")) &&
                ((Number) docs.get(0).getMetadata().get("importance")).doubleValue() >= 0.8
        ));
    }

    @Test
    void onReflectionRequest_skipsWhenNotEnoughEpisodes() {
        // episodeThreshold=4, threshold/2=2, only 1 episode → skip
        when(vectorStore.similaritySearch(any())).thenReturn(
                List.of(new Document("1", "only one episode", Map.of()))
        );

        reflectionWorker.onReflectionRequest(new ReflectionRequestEvent("session1"));

        verify(chatClient, never()).prompt();
        verify(vectorStore, never()).add(any());
    }

    @Test
    void onReflectionRequest_handlesLLMFailureGracefully() {
        List<Document> episodes = List.of(
                new Document("1", "e1", Map.of()),
                new Document("2", "e2", Map.of()),
                new Document("3", "e3", Map.of()),
                new Document("4", "e4", Map.of())
        );
        when(vectorStore.similaritySearch(any())).thenReturn(episodes);
        when(callSpec.content()).thenThrow(new RuntimeException("LLM error"));

        reflectionWorker.onReflectionRequest(new ReflectionRequestEvent("session1"));

        verify(vectorStore, never()).add(any());
    }
}
