package com.dawn.ai.memory;

import com.dawn.ai.memory.entity.MemoryEntity;
import com.dawn.ai.memory.entity.MemoryHistoryEntity;
import com.dawn.ai.memory.repository.MemoryHistoryRepository;
import com.dawn.ai.memory.repository.MemoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryManager {

    private final MemoryRepository memoryRepository;
    private final MemoryHistoryRepository historyRepository;
    private final VectorStore vectorStore;

    @Value("${app.memory.search.importance-weight:0.3}")
    private double importanceWeight;

    // 重排前多取候选的倍数，让高 importance 记忆有机会从被截断区冠头
    private static final int SEARCH_CANDIDATE_MULTIPLIER = 2;

    private static final String VECTOR_COLLECTION = "memory_entries";

    @Transactional
    public String add(String userId, String sessionId, String content, MemoryType type, double importance) {
        String hash = md5(content);

        Optional<MemoryEntity> existing = memoryRepository.findByHashAndUserIdAndDeletedFalse(hash, userId);
        if (existing.isPresent()) {
            log.debug("[MemoryManager] Duplicate memory skipped for user={}, hash={}", userId, hash);
            return existing.get().getId().toString();
        }

        Instant now = Instant.now();
        UUID memoryId = UUID.randomUUID();

        // Write VectorStore first — if it fails, JPA won't commit
        Document doc = new Document(
                memoryId.toString(),
                content,
                Map.of(
                        "type", type.name().toLowerCase(),
                        "userId", userId,
                        "sessionId", sessionId != null ? sessionId : "",
                        "importance", importance,
                        "hash", hash,
                        "createdAt", now.toEpochMilli(),
                        "lastAccessedAt", now.toEpochMilli()
                )
        );
        vectorStore.add(List.of(doc));

        MemoryEntity entity = new MemoryEntity();
        entity.setId(memoryId);
        entity.setUserId(userId);
        entity.setSessionId(sessionId);
        entity.setContent(content);
        entity.setMemoryType(type);
        entity.setImportance(importance);
        entity.setHash(hash);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setLastAccessedAt(now);
        entity.setDeleted(false);
        memoryRepository.save(entity);

        try {
            addHistory(entity.getId(), null, content, "ADD");
        } catch (Exception e) {
            log.warn("[MemoryManager] Failed to write history for memory={}: {}", entity.getId(), e.getMessage());
        }

        log.info("[MemoryManager] Added memory id={}, type={}, importance={}", entity.getId(), type, importance);
        return entity.getId().toString();
    }

    @Transactional
    public String addWithDedup(String userId, String sessionId, String content, MemoryType type, double importance) {
        String hash = md5(content);

        Optional<MemoryEntity> existing = memoryRepository.findByHashAndUserIdAndDeletedFalse(hash, userId);
        if (existing.isPresent()) {
            MemoryEntity entity = existing.get();
            if (Math.abs(entity.getImportance() - importance) > 0.01) {
                entity.setImportance(Math.max(entity.getImportance(), importance));
                entity.setUpdatedAt(Instant.now());
                memoryRepository.save(entity);
            }
            return entity.getId().toString();
        }

        return add(userId, sessionId, content, type, importance);
    }

    public List<MemorySearchResult> search(String userId, String query, int topK) {
        return search(userId, query, topK, null);
    }

    public List<MemorySearchResult> search(String userId, String query, int topK, MemoryType type) {
        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        var filter = fb.eq("userId", userId);
        if (type != null) {
            filter = fb.and(filter, fb.eq("type", type.name().toLowerCase()));
        }

        List<Document> results;
        try {
            results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK * SEARCH_CANDIDATE_MULTIPLIER)
                            .filterExpression(filter.build())
                            .build());
        } catch (Exception e) {
            log.warn("[MemoryManager] VectorStore search failed for user={}: {}", userId, e.getMessage());
            return List.of();
        }

        // Post-filter: verify documents still exist in JPA and are not soft-deleted
        List<String> ids = results.stream().map(Document::getId).toList();
        Set<UUID> existingIds = memoryRepository.findAllById(ids.stream().map(id -> parseUuidSafe(id)).filter(Objects::nonNull).toList())
                .stream().filter(e -> !e.isDeleted()).map(MemoryEntity::getId).collect(Collectors.toSet());

        List<Document> filtered = results.stream()
                .filter(doc -> {
                    UUID docId = parseUuidSafe(doc.getId());
                    return docId != null && existingIds.contains(docId);
                })
                .toList();

        // importance 加权重排：finalScore = similarity * (1 + w * importance)，再截断到 topK
        List<MemorySearchResult> ranked = filtered.stream()
                .map(doc -> new MemorySearchResult(
                        doc.getId(),
                        doc.getText(),
                        doc.getMetadata().getOrDefault("type", "unknown").toString(),
                        toDouble(doc.getMetadata().get("importance")),
                        doc.getScore()
                ))
                .sorted(Comparator.comparingDouble(this::weightedScore).reversed())
                .limit(topK)
                .toList();

        // 仅对最终返回的记忆刷新访问时间
        Set<String> finalIds = ranked.stream().map(MemorySearchResult::id).collect(Collectors.toSet());
        updateAccessTime(filtered.stream().filter(doc -> finalIds.contains(doc.getId())).toList());

        return ranked;
    }

    private double weightedScore(MemorySearchResult r) {
        double similarity = r.score() != null ? r.score() : 0.0;
        return similarity * (1 + importanceWeight * r.importance());
    }

    @Transactional
    public boolean update(String memoryId, String newContent) {
        Optional<MemoryEntity> opt = memoryRepository.findById(parseUuid(memoryId));
        if (opt.isEmpty() || opt.get().isDeleted()) {
            log.warn("[MemoryManager] Memory not found for update: {}", memoryId);
            return false;
        }

        MemoryEntity entity = opt.get();
        String oldContent = entity.getContent();
        String newHash = md5(newContent);

        entity.setContent(newContent);
        entity.setHash(newHash);
        entity.setUpdatedAt(Instant.now());
        memoryRepository.save(entity);

        try {
            Document doc = new Document(
                    entity.getId().toString(),
                    newContent,
                    Map.of(
                            "type", entity.getMemoryType().name().toLowerCase(),
                            "userId", entity.getUserId(),
                            "sessionId", entity.getSessionId() != null ? entity.getSessionId() : "",
                            "importance", entity.getImportance(),
                            "hash", newHash,
                            "createdAt", entity.getCreatedAt().toEpochMilli(),
                            "lastAccessedAt", entity.getLastAccessedAt().toEpochMilli()
                    )
            );
            vectorStore.add(List.of(doc));
        } catch (Exception e) {
            log.warn("[MemoryManager] VectorStore update failed for memory={}: {}", memoryId, e.getMessage());
        }

        addHistory(entity.getId(), oldContent, newContent, "UPDATE");
        log.info("[MemoryManager] Updated memory id={}", memoryId);
        return true;
    }

    @Transactional
    public boolean delete(String memoryId) {
        Optional<MemoryEntity> opt = memoryRepository.findById(parseUuid(memoryId));
        if (opt.isEmpty()) {
            log.warn("[MemoryManager] Memory not found for delete: {}", memoryId);
            return false;
        }

        MemoryEntity entity = opt.get();
        entity.setDeleted(true);
        entity.setUpdatedAt(Instant.now());
        memoryRepository.save(entity);

        // VectorStore cleanup is best-effort; JPA soft-delete is the source of truth.
        // search() post-filters against JPA, so a stale VectorStore doc won't surface.
        try {
            vectorStore.delete(List.of(memoryId));
        } catch (Exception e) {
            log.warn("[MemoryManager] VectorStore delete failed for memory={}, search post-filter will exclude it: {}",
                    memoryId, e.getMessage());
        }

        addHistory(entity.getId(), entity.getContent(), null, "DELETE");
        log.info("[MemoryManager] Deleted memory id={}", memoryId);
        return true;
    }

    public Optional<MemoryEntity> get(String memoryId) {
        return memoryRepository.findById(parseUuid(memoryId))
                .filter(e -> !e.isDeleted());
    }

    public List<MemoryEntity> getAll(String userId) {
        return memoryRepository.findByUserIdAndDeletedFalseOrderByImportanceDesc(userId);
    }

    public List<MemoryHistoryEntity> history(String memoryId) {
        return historyRepository.findByMemoryIdOrderByCreatedAtAsc(parseUuid(memoryId));
    }

    public long countBySession(String userId, String sessionId) {
        return memoryRepository.countByUserIdAndSessionIdAndDeletedFalse(userId, sessionId);
    }

    private void addHistory(UUID memoryId, String oldContent, String newContent, String event) {
        MemoryHistoryEntity history = new MemoryHistoryEntity();
        history.setId(UUID.randomUUID());
        history.setMemoryId(memoryId);
        history.setOldContent(oldContent);
        history.setNewContent(newContent);
        history.setEvent(event);
        history.setCreatedAt(Instant.now());
        historyRepository.save(history);
    }

    private void updateAccessTime(List<Document> docs) {
        List<UUID> ids = docs.stream()
                .filter(doc -> doc.getMetadata().containsKey("type"))
                .map(doc -> UUID.fromString(doc.getId()))
                .toList();
        if (!ids.isEmpty()) {
            try {
                memoryRepository.updateLastAccessedAt(ids, Instant.now());
            } catch (Exception e) {
                log.debug("[MemoryManager] Failed to update lastAccessedAt: {}", e.getMessage());
            }
        }
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 digest failed", e);
        }
    }

    private static UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid memory ID format: " + id, e);
        }
    }

    private static UUID parseUuidSafe(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return 0.0;
    }

    public record MemorySearchResult(String id, String content, String type, double importance, Double score) {
    }
}
