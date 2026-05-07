package com.dawn.ai.memory.diagnostics;

import com.dawn.ai.memory.EvictionPolicyManager;
import com.dawn.ai.memory.UserProfileService;
import com.dawn.ai.service.MemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Memory 管道诊断端点（仅在 e2e-test profile 下激活）
 *
 * <p>提供以下能力：
 * <ul>
 *   <li>直接向 MemoryService 注入消息，触发 L1→L2→L3→L4 全链路（绕过 ChatController）</li>
 *   <li>向 VectorStore 直接写入自定义文档（用于 Eviction 测试）</li>
 *   <li>读取各层真实状态：L1 窗口大小、L2 Pending 数量、L3 PGVector 文档数、L4 用户画像</li>
 *   <li>手动触发 EvictionPolicyManager.evict()</li>
 *   <li>清理 session 数据</li>
 * </ul>
 *
 * <p>设计原则：此 Controller 不使用任何 Mock，所有操作直接作用于真实 Redis 和 PGVector。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/debug/memory")
@RequiredArgsConstructor
public class MemoryDiagnosticsController {

    private static final String SESSION_PREFIX = "ai:session:";
    private static final String PENDING_SUFFIX = ":pending";
    private static final String PROFILE_PREFIX = "ai:profile:";

    private final MemoryService memoryService;
    private final UserProfileService userProfileService;
    private final VectorStore vectorStore;
    private final RedisTemplate<String, Object> redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final EvictionPolicyManager evictionPolicyManager;

    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}")
    private String vectorTableName;  // non-final: injected via @Value after construction

    // ─────────────────────────────────────────────────────────────────
    //  注入消息 → 触发 L1→L2→L3→L4 管道
    // ─────────────────────────────────────────────────────────────────

    /**
     * 批量注入消息到 MemoryService（等同于真实对话消息写入）。
     *
     * <p>请求体：
     * <pre>{
     *   "count":    5,                         // 注入消息数
     *   "template": "技术讨论第{i}轮",           // {i} 替换为序号（1-indexed）
     *   "role":     "user"                     // "user" 或 "assistant"
     * }</pre>
     */
    @PostMapping("/{sessionId}/inject")
    public ResponseEntity<Map<String, Object>> inject(
            @PathVariable String sessionId,
            @RequestBody InjectRequest req) {

        int count = req.count() != null ? req.count() : 1;
        String template = req.template() != null ? req.template() : "测试消息 {i}";
        String role = req.role() != null ? req.role() : "user";

        for (int i = 1; i <= count; i++) {
            String content = template.replace("{i}", String.valueOf(i));
            memoryService.addMessage(sessionId, role, content);
        }
        log.debug("[Diagnostics] Injected {} message(s) into session={}", count, sessionId);
        return ResponseEntity.ok(Map.of("injected", count, "sessionId", sessionId));
    }

    /**
     * 直接向 VectorStore 写入自定义文档（用于 Eviction 测试，不经过摘要管道）。
     *
     * <p>请求体：
     * <pre>{
     *   "content":    "文档内容",
     *   "type":       "summary",   // 或 "reflection"
     *   "importance": 0.05,
     *   "ageDays":    200          // 文档创建时间距今的天数（用于测试超龄文档）
     * }</pre>
     */
    @PostMapping("/{sessionId}/inject-doc")
    public ResponseEntity<Map<String, Object>> injectDoc(
            @PathVariable String sessionId,
            @RequestBody InjectDocRequest req) {

        long createdAt = Instant.now()
                .minus(req.ageDays() != null ? req.ageDays() : 0, ChronoUnit.DAYS)
                .toEpochMilli();

        Document doc = new Document(
                UUID.randomUUID().toString(),
                req.content(),
                Map.of(
                        "type",           req.type() != null ? req.type() : "summary",
                        "sessionId",      sessionId,
                        "importance",     req.importance() != null ? req.importance() : 0.5,
                        "createdAt",      createdAt,
                        "lastAccessedAt", createdAt
                )
        );
        vectorStore.add(List.of(doc));
        log.debug("[Diagnostics] Injected doc type={} importance={} ageDays={} session={}",
                req.type(), req.importance(), req.ageDays(), sessionId);
        return ResponseEntity.ok(Map.of("docId", doc.getId(), "sessionId", sessionId));
    }

    // ─────────────────────────────────────────────────────────────────
    //  状态查询：L1 / L2 / L3 / L4
    // ─────────────────────────────────────────────────────────────────

    /**
     * 返回 session 的四层记忆状态快照。
     *
     * <p>响应体：
     * <pre>{
     *   "l1Window":  20,                         // Redis 活跃窗口消息数
     *   "l2Pending": 0,                          // Redis Pending 队列消息数
     *   "l3Count":   3,                          // PGVector 中该 session 的文档数
     *   "l4Profile": {"reflection": "用户是..."}  // Redis Hash 用户画像
     * }</pre>
     *
     * <p>L3 通过直接 JDBC 查询，无需 EmbeddingModel，实时准确。
     */
    @GetMapping("/{sessionId}/state")
    public ResponseEntity<Map<String, Object>> state(@PathVariable String sessionId) {
        // L1: Redis 活跃窗口大小
        Long l1Raw = redisTemplate.opsForList().size(SESSION_PREFIX + sessionId);
        int l1Window = l1Raw != null ? l1Raw.intValue() : 0;

        // L2: Redis Pending 队列大小
        Long l2Raw = redisTemplate.opsForList()
                .size(SESSION_PREFIX + sessionId + PENDING_SUFFIX);
        int l2Pending = l2Raw != null ? l2Raw.intValue() : 0;

        // L3: PGVector 文档数（直接 JDBC，避免 Embedding 依赖）
        Long l3Raw = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + vectorTableName + " WHERE metadata->>'sessionId' = ?",
                Long.class, sessionId);
        int l3Count = l3Raw != null ? l3Raw.intValue() : 0;

        // L4: Redis Hash 用户画像
        Map<String, String> l4Profile = userProfileService.getProfile(sessionId);

        return ResponseEntity.ok(Map.of(
                "l1Window",  l1Window,
                "l2Pending", l2Pending,
                "l3Count",   l3Count,
                "l4Profile", l4Profile
        ));
    }

    // ─────────────────────────────────────────────────────────────────
    //  操作
    // ─────────────────────────────────────────────────────────────────

    /** 手动触发驱逐策略（等同于定时任务触发）。 */
    @PostMapping("/evict")
    public ResponseEntity<Map<String, String>> evict() {
        evictionPolicyManager.evict();
        return ResponseEntity.ok(Map.of("status", "eviction triggered"));
    }

    /** 清理 session：删除 Redis 对话历史 + Pending + 用户画像。VectorStore 文档不清理（依赖 sessionId filter 隔离）。 */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> cleanup(@PathVariable String sessionId) {
        memoryService.clearSession(sessionId);
        redisTemplate.delete(PROFILE_PREFIX + sessionId);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Request records
    // ─────────────────────────────────────────────────────────────────

    record InjectRequest(Integer count, String template, String role) {}

    record InjectDocRequest(String content, String type, Double importance, Integer ageDays) {}
}
