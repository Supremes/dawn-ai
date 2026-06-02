# Memory Mem0 Alignment - Code Review Report

**Date**: 2026-06-02
**Branch**: feat/memory-mem0-alignment
**Reviewer**: 5-angle max-effort review (line-by-line, removed-behavior, cross-file, language-pitfall, wrapper/proxy)

## Findings (13 items, severity ranked)

### P0 - 必须修复

| # | File | Line | Summary | Failure Scenario |
|---|------|------|---------|-----------------|
| 1 | `MemoryConsolidator.java` | 38 | FactExtractor 和 MemorySummarizer 都监听 SummarizationRequestEvent，两个 handler 都调用 triggerReflectionIfNeeded()，反射触发频率翻倍 | reflectionThreshold=3 时第 2 次就触发，平均每 1.5 次请求触发一次 |
| 2 | `MemoryManager.java` | 247 | md5() 使用 input.getBytes()（平台默认编码），不同 OS 产生不同 hash，去重失效 | macOS UTF-8 vs Linux ISO-8859-1 编码下相同文本产生不同 MD5 |
| 3 | `MemoryManager.java` | 254 | md5() fallback 用 hashCode()，JVM 重启后不稳定且格式不匹配 | 应用重启后相同字符串 hashCode 可能不同，去重失效 |
| 4 | `EvictionPolicyManager.java` | 31 | maxAgeDays 默认值从 180 改为 2，生产环境未配置时会批量清除记忆 | application.yml 未设置 max-age-days，上线后 03:00 批量清除 |
| 5 | `MemoryManager.java` | 105 | search() 过滤条件 fb.ne("deleted", true) 引用 VectorStore metadata 中不存在的字段 | delete() VectorStore 删除失败时，search() 仍返回已删除记忆 |
| 6 | `MemoryManager.java` | 55 | add() 双写失败：VectorStore 写入失败后 JPA 已提交，记忆永远无法被搜索到，且去重阻止重新添加 | pgvector 超时 → JPA 记录存在 → 相同内容 add 被 hash 去重跳过 |
| 7 | `MemoryManager.java` | 74 | addHistory() 在 @Transactional 内，若抛异常导致回滚，VectorStore 已写入无法回滚 | addHistory DB 约束冲突 → JPA 回滚，VectorStore 孤立 document |
| 8 | `MemoryManager.java` | 192 | delete() JPA 软删除已提交但 VectorStore 硬删除失败时，search 仍返回该记忆 | vectorStore.delete 抛异常 → search 返回已删除记忆，get 返回 empty |

### P1 - 应该修复

| # | File | Line | Summary | Failure Scenario |
|---|------|------|---------|-----------------|
| 9 | `EvictionPolicyManager.java` | 37 | evict() @Transactional 范围过宽，中间 RuntimeException 导致全部回滚 | 500 条中第 3 条 ID 异常 → 全部回滚 |
| 10 | `MemoryManager.java` | 139 | UUID.fromString 无防护，非法字符串抛 500 | 外部传入 "abc" → IllegalArgumentException → 500 |
| 11 | `MemoryRepository.java` | 45 | updateLastAccessedAt @Modifying 无 @Transactional，异步调用路径失败 | MemoryAccessUpdater @Async 线程调用 → TransactionRequiredException |
| 12 | `MemoryEntity.java` | 12 | @Data 生成 equals/hashCode 包含可变字段，放入 HashSet 后修改会丢失 | entity 放入 HashSet 后 importance 更新 → hashCode 变化 → 查找失败 |
| 13 | `MemoryManager.java` | 49 | Instant.now() 多次调用 + @PrePersist 可能覆盖，时间戳不一致 | entity.setCreatedAt(now1) → @PrePersist 覆盖为 now2 → VectorStore 用 now1 |
