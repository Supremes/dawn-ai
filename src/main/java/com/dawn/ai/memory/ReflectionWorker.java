package com.dawn.ai.memory;

import com.dawn.ai.memory.event.ReflectionRequestEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReflectionWorker {

    private final MemoryManager memoryManager;
    private final ChatClient chatClient;
    private final UserProfileService userProfileService;
    private final int episodeThreshold;

    private static final String REFLECT_PROMPT =
            "以下是用户的多段对话摘要，请从中提炼出用户的长期偏好、习惯和重要特征（200字以内）。\n" +
            "摘要集合:\n%s\n用户画像提炼:";

    public ReflectionWorker(
            MemoryManager memoryManager,
            ChatClient chatClient,
            UserProfileService userProfileService,
            @Value("${app.memory.reflection.episode-threshold:4}") int episodeThreshold) {
        this.memoryManager = memoryManager;
        this.chatClient = chatClient;
        this.userProfileService = userProfileService;
        this.episodeThreshold = episodeThreshold;
    }

    @EventListener
    @Async
    public void onReflectionRequest(ReflectionRequestEvent event) {
        List<MemoryManager.MemorySearchResult> episodes = memoryManager.search(
                event.userId(), "用户偏好和习惯", episodeThreshold, MemoryType.EPISODIC);

        if (episodes.size() < episodeThreshold / 2) {
            log.debug("[ReflectionWorker] Not enough episodes ({}) for session={}", episodes.size(), event.sessionId());
            return;
        }

        String episodesText = episodes.stream()
                .map(MemoryManager.MemorySearchResult::content)
                .collect(Collectors.joining("\n---\n"));

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

        try {
            memoryManager.add(event.userId(), event.sessionId(), reflection, MemoryType.PROCEDURAL, 0.9);
            userProfileService.upsertAttribute(event.userId(), "reflection", reflection);
            log.info("[ReflectionWorker] Reflection persisted for session={}", event.sessionId());
        } catch (Exception e) {
            log.warn("[ReflectionWorker] Failed to persist reflection for session={}: {}", event.sessionId(), e.getMessage());
        }
    }
}
