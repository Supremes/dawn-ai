package com.dawn.ai.memory;

import com.dawn.ai.memory.repository.MemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class MemoryAccessUpdater {

    private final MemoryRepository memoryRepository;

    public MemoryAccessUpdater(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @Async
    public void updateAccessTime(List<Document> docs) {
        List<UUID> ids = docs.stream()
                .filter(doc -> doc.getMetadata().containsKey("type"))
                .map(doc -> UUID.fromString(doc.getId()))
                .toList();

        if (ids.isEmpty()) {
            return;
        }

        try {
            memoryRepository.updateLastAccessedAt(ids, Instant.now());
            log.debug("[MemoryAccessUpdater] Updated lastAccessedAt for {} memory docs", ids.size());
        } catch (Exception e) {
            log.warn("[MemoryAccessUpdater] Failed to update lastAccessedAt: {}", e.getMessage());
        }
    }
}
