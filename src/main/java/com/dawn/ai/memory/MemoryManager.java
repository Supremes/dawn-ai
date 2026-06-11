package com.dawn.ai.memory;

import com.dawn.ai.memory.entity.MemoryEntity;
import com.dawn.ai.memory.entity.MemoryHistoryEntity;
import com.dawn.ai.memory.repository.MemoryHistoryRepository;
import com.dawn.ai.memory.repository.MemoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
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
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    @Value("${app.memory.search.importance-weight:0.3}")
    private double importanceWeight;

    @Value("${app.memory.dedup.semantic-enabled:true}")
    private boolean semanticDedupEnabled;

    @Value("${app.memory.dedup.semantic-top-k:3}")
    private int semanticTopK;

    // 重排前多取候选的倍数，让高 importance 记忆有机会从被截断区冠头
    private static final int SEARCH_CANDIDATE_MULTIPLIER = 2;

        private static final String SEMANTIC_DEDUP_PROMPT = """
                        你是一个长期记忆去重判断器。请判断“新记忆”是否与候选记忆中的某一条表达同一个可保存事实。

                        判定标准：
                        - 只有当两条记忆表达的是同一个事实、偏好、身份信息、计划或稳定约束时，才判为重复。
                        - 同义改写、语言不同但含义相同、细节粒度略有差异但不改变事实核心，可以判为重复。
                        - 只是主题相近、实体不同、时间不同、偏好冲突、范围更宽或更窄导致事实不能互相替代时，不要判为重复。
                        - 如果多条候选都像重复项，选择最能被新记忆替代的一条。
                        - 候选记忆只提供 key，不提供数据库 ID；duplicateKey 必须原样返回候选中的 key。
                        - 只能返回 JSON，不要返回 Markdown 或解释文本。

                        Few-shot 示例：

                        示例 1：同义改写，应判重复
                        新记忆：{"type":"semantic","content":"用户喜欢喝低糖拿铁。"}
                        候选记忆：[{"key":"c1","type":"semantic","content":"用户偏好少糖拿铁。"}]
                        输出：{"duplicate":true,"duplicateKey":"c1","reason":"两条都表示用户喜欢少糖/低糖拿铁。"}

                        示例 2：主题相近但事实不同，不重复
                        新记忆：{"type":"semantic","content":"用户喜欢跑步。"}
                        候选记忆：[{"key":"c1","type":"semantic","content":"用户喜欢骑行。"}]
                        输出：{"duplicate":false,"duplicateKey":null,"reason":"两条都是运动偏好，但具体活动不同。"}

                        示例 3：候选中只有一条重复，返回对应 key
                        新记忆：{"type":"procedural","content":"用户希望代码回答先给结论再解释。"}
                        候选记忆：[
                            {"key":"c1","type":"procedural","content":"用户要求回复使用中文。"},
                            {"key":"c2","type":"procedural","content":"用户喜欢先看结论，然后再看原因。"}
                        ]
                        输出：{"duplicate":true,"duplicateKey":"c2","reason":"c2 与新记忆都描述先结论后解释的回答偏好。"}

                        请判断下面的新记忆是否与候选记忆重复。

                        新记忆：%s

                        候选记忆：%s

                        输出 JSON schema：{"duplicate":boolean,"duplicateKey":string|null,"reason":string}
                        """;

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

        // Write VectorStore first — if it fails, JPA won't commit
        upsertVectorDocument(entity);
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

        // 1) 精确去重：hash 完全相同
        Optional<MemoryEntity> exact = memoryRepository.findByHashAndUserIdAndDeletedFalse(hash, userId);
        if (exact.isPresent()) {
            return bumpImportance(exact.get(), importance);
        }

        // 2) 语义去重：先召回同类型候选，再由 LLM 判断是否表达同一个事实
        //    复用 search（已含 JPA post-filter，返回项必存活），检索或 LLM 失败不阻塞写入
        if (semanticDedupEnabled) {
            Optional<MemoryEntity> semanticDup = findSemanticDuplicate(userId, content, type);
            if (semanticDup.isPresent()) {
                return bumpImportance(semanticDup.get(), importance);
            }
        }

        return add(userId, sessionId, content, type, importance);
    }

    private String bumpImportance(MemoryEntity entity, double importance) {
        Instant now = Instant.now();
        double reinforcedImportance = Math.min(
                1.0,
                Math.max(entity.getImportance(), importance) + 0.05
        );

        entity.setImportance(reinforcedImportance);
        entity.setUpdatedAt(now);
        entity.setLastAccessedAt(now);
        upsertVectorDocument(entity);
        memoryRepository.save(entity);

        return entity.getId().toString();
    }

    private Optional<MemoryEntity> findSemanticDuplicate(String userId, String content, MemoryType type) {
        List<MemorySearchResult> candidates = search(userId, content, Math.max(1, semanticTopK), type);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return decideSemanticDuplicate(content, type, candidates)
                .flatMap(id -> memoryRepository.findById(parseUuid(id)))
                .filter(e -> !e.isDeleted());
    }

    private Optional<String> decideSemanticDuplicate(String content,
                                                     MemoryType type,
                                                     List<MemorySearchResult> candidates) {
        Map<String, String> candidateKeyToId = buildCandidateKeyMap(candidates);
        try {
            String prompt = buildSemanticDedupPrompt(content, type, candidates, candidateKeyToId);
            String response = chatClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder().temperature(0.0).build())
                    .call()
                    .content();

            JsonNode decision = parseJsonObject(response);
            if (!decision.path("duplicate").asBoolean(false)) {
                return Optional.empty();
            }

            String duplicateKey = decision.path("duplicateKey").asText("");
            String duplicateId = candidateKeyToId.get(duplicateKey);
            if (duplicateId == null) {
                log.warn("[MemoryManager] LLM dedup returned invalid duplicateKey={}, candidates={}",
                        duplicateKey, candidateKeyToId.keySet());
                return Optional.empty();
            }

            log.info("[MemoryManager] LLM semantic duplicate detected: duplicateKey={}, duplicateId={}, reason={}",
                    duplicateKey, duplicateId, decision.path("reason").asText(""));
            return Optional.of(duplicateId);
        } catch (Exception e) {
            log.warn("[MemoryManager] LLM semantic dedup failed, memory will be inserted: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String buildSemanticDedupPrompt(String content,
                                            MemoryType type,
                                            List<MemorySearchResult> candidates,
                                            Map<String, String> candidateKeyToId) throws Exception {
        Map<String, String> newMemory = Map.of(
                "type", type.name().toLowerCase(),
                "content", content
        );
        List<DedupCandidate> dedupCandidates = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            MemorySearchResult candidate = candidates.get(i);
            String key = "c" + (i + 1);
            if (candidateKeyToId.containsKey(key)) {
                dedupCandidates.add(new DedupCandidate(
                        key,
                        candidate.content(),
                        candidate.type(),
                        candidate.importance(),
                        candidate.score()
                ));
            }
        }
        return SEMANTIC_DEDUP_PROMPT.formatted(
                objectMapper.writeValueAsString(newMemory),
                objectMapper.writeValueAsString(dedupCandidates)
        );
    }

    private Map<String, String> buildCandidateKeyMap(List<MemorySearchResult> candidates) {
        Map<String, String> candidateKeyToId = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            candidateKeyToId.put("c" + (i + 1), candidates.get(i).id());
        }
        return candidateKeyToId;
    }

    private JsonNode parseJsonObject(String response) throws Exception {
        String json = extractJsonObjectPayload(response);
        if (json.isBlank()) {
            throw new IllegalArgumentException("LLM dedup response does not contain a JSON object");
        }
        return objectMapper.readTree(json);
    }

    private static String extractJsonObjectPayload(String response) {
        if (response == null) {
            return "";
        }
        String text = response.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return text.substring(start, end + 1);
    }

    public List<MemorySearchResult> search(String userId, String query, int topK, MemoryType type) {
        log.debug("记忆查询 - userId: {}, query: {}, topK: {}, type: {}", userId, query, topK, type);
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

        if (results.isEmpty()) {
            log.debug("记忆查询 - 向量查询结果为空, 没必要使用JPA查询验证了");
            return List.of();
        }

        // Post-filter: verify documents still exist in JPA and are not soft-deleted
        log.debug("记忆查询 - JPA 查询");
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

        log.debug("记忆查询 - 命中{}条", ranked);
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
            upsertVectorDocument(entity);
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

    private void upsertVectorDocument(MemoryEntity entity) {
        vectorStore.add(List.of(toVectorDocument(entity)));
    }

    private Document toVectorDocument(MemoryEntity entity) {
        return new Document(
                entity.getId().toString(),
                entity.getContent(),
                Map.of(
                        "type", entity.getMemoryType().name().toLowerCase(),
                        "userId", entity.getUserId(),
                        "sessionId", entity.getSessionId() != null ? entity.getSessionId() : "",
                        "importance", entity.getImportance(),
                        "hash", entity.getHash(),
                        "createdAt", entity.getCreatedAt().toEpochMilli(),
                        "lastAccessedAt", entity.getLastAccessedAt().toEpochMilli()
                )
        );
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

    private record DedupCandidate(String key, String content, String type, double importance, Double score) {
    }
}
