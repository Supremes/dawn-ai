package com.dawn.ai.memory;

import com.dawn.ai.config.PromptManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
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
    private final UserProfileService userProfileService;
    private final PromptManager promptManager;
    private final int episodeThreshold;

    public ReflectionWorker(
            VectorStore vectorStore,
            ChatClient chatClient,
            UserProfileService userProfileService,
            PromptManager promptManager,
            @Value("${app.memory.reflection.episode-threshold:10}") int episodeThreshold) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
        this.userProfileService = userProfileService;
        this.promptManager = promptManager;
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
                    .user(promptManager.render("reflection-worker", Map.of("summaries", episodesText)))
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
            userProfileService.upsertAttribute(event.sessionId(), "reflection", reflection);
            log.info("[ReflectionWorker] Reflection persisted for session={}", event.sessionId());
        } catch (Exception e) {
            log.warn("[ReflectionWorker] VectorStore write failed session={}: {}", event.sessionId(), e.getMessage());
        }
    }
}
