# Commit 范围摘要：20befc9 → a7a9433

**日期**：2026-04-30  
**分支**：feature/memory-management  
**改动规模**：20 个文件，+2497 / -33 行

---

## 总览

本次提交范围实现了 **P0 记忆生命周期**的完整工程落地，涵盖短期记忆（Redis 会话窗口）→ 摘要压缩 → 长期记忆（VectorStore）→ 反思提炼 → 用户画像注入 → 定时淘汰的全链路，采用事件驱动架构串联各组件。



这条分支的目标是把项目原有"只有 Redis 滚动窗口 + 简单 RAG"的记忆能力，升级为一套覆盖**写入 → 压缩 → 固化 → 反思 → 召回 → 衰减**完整生命周期的记忆系统。

---

## 逐提交说明

### 1. `20befc9` — feat(memory): enable scheduling and async for memory lifecycle

在 `DawnAiApplication` 上添加 `@EnableScheduling` 和 `@EnableAsync`，为后续调度任务和异步监听器提供基础支撑。

### 2. `69e7a3c` — feat(memory): add app.memory config keys

在 `application.yml` 中新增 `app.memory` 配置节：

| 配置项                                  | 默认值           | 说明                    |
| ------------------------------------ | ------------- | --------------------- |
| `summary.batch-size`                 | 5             | 触发摘要的消息批次大小           |
| `consolidation.reflection-threshold` | 10            | 触发反思的摘要积累阈值           |
| `eviction.cron`                      | `0 0 3 * * ?` | 淘汰任务调度（每天凌晨 3 点）      |
| `eviction.importance-threshold`      | 0.1           | 低于此 importance 的条目被淘汰 |
| `eviction.max-age-days`              | 180           | 超过此天数的条目被淘汰           |
| `reflection.episode-threshold`       | 10            | 触发反思的 episode 阈值      |

### 3. `a07010b` — feat(memory): redis failsafe + summary buffer event trigger

**`MemoryService`（大幅增强）**
- 新增 Redis 降级：写入失败时切换为 `ConcurrentHashMap` 内存 fallback，双 `Counter` metric（`agent.memory.redis.failure`）追踪读写失败次数。
- 新增 pending buffer：当 Redis 主列表超出 `MAX_HISTORY(20)` 时，溢出消息推入 `:{sessionId}:pending` 副列表；pending 积累至 `summaryBatchSize` 后**原子 drain**（`RENAME` 技巧）并发布 `SummarizationRequestEvent`。
- 新增 `SummarizationRequestEvent`（携带 sessionId + 消息批次）。
- 新增 `MemoryServiceTest`（86 行）。

### 4. `0569693` — feat(memory): summary buffer - async LLM compression of evicted messages

**`MemorySummarizer`（新增）**
- `@EventListener` + `@Async` 监听 `SummarizationRequestEvent`。
- 调用 LLM（中文 prompt）将对话批次压缩为 ≤100 字摘要。
- 摘要完成后发布 `ConsolidationRequestEvent`（携带 sessionId + `SummaryResult`）。

**新增事件/值对象**：`ConsolidationRequestEvent`、`SummaryResult`（record）。  
**新增测试**：`MemorySummarizerTest`（74 行）。

### 5. `63f2508` — fix(memory): null-safe message join + precise test assertion

修复 `MemorySummarizer` 中 `content` 可能为 null 时的 NPE；同步修正测试断言精度。

### 6. `d9c9fd1` — feat(memory): consolidation - persist summaries to VectorStore with reflection trigger

**`MemoryConsolidator`（新增）**
- 监听 `ConsolidationRequestEvent`，将摘要以 `Document`（含 `sessionId`、`type=summary`、`importance`、`createdAt` 元数据）持久化到 VectorStore。
- 用 `ConcurrentHashMap<String, AtomicInteger>` 跟踪每个 session 的累计摘要数；达到 `reflectionThreshold` 时发布 `ReflectionRequestEvent`，触发反思。

**新增事件**：`ReflectionRequestEvent`。  
**新增测试**：`MemoryConsolidatorTest`（64 行）。

### 7. `e263485` — feat(memory): user profile / hard memory injected into system prompt

**`UserProfileService`（新增）**
- Redis Hash 存储用户画像（key-value 属性），TTL 30 天（key 前缀 `ai:profile:`）。
- 提供 `upsertAttribute`、`getProfile`、`formatForPrompt` 方法。

**`AgentOrchestrator`（增强）**
- 注入 `UserProfileService`，在构建系统 prompt 时追加用户画像段落（"hard memory"），实现跨会话个性化。

**新增测试**：`UserProfileServiceTest`（56 行）。

### 8. `c991bc6` — feat(memory): decay/eviction - scheduled removal of stale low-importance summaries

**`EvictionPolicyManager`（新增）**
- `@Scheduled` 任务（cron 可配），用探针查询向量库中的 summary 类型条目（批次 100）。
- 淘汰条件：`importance < threshold` **或** 创建时间超过 `maxAgeDays`。
- 跳过 `type=profile` 类型的反思画像条目（防止误删）。

**新增测试**：`EvictionPolicyManagerTest`（72 行）。

### 9. `8aafb8f` — feat(memory): reflection - LLM-based pattern extraction to high-importance VectorStore entry

**`ReflectionWorker`（新增）**
- 监听 `ReflectionRequestEvent`，从 VectorStore 召回该 session 最近的 summary 条目。
- 调用 LLM（中文 prompt）提炼用户长期偏好、习惯、重要特征（≤200 字）。
- 将反思结果以 `importance=1.0`、`type=profile` 写入 VectorStore（高重要度，不被淘汰）。
- 将结构化属性写入 `UserProfileService`，供下次对话注入 prompt。

**新增测试**：`ReflectionWorkerTest`（88 行）。

### 10. `a7a9433` — fix(memory): CAS threshold, atomic drain, reflection updates profile, eviction guards reflection

一组跨组件的并发安全和逻辑修复：
- **`MemoryConsolidator`**：反思触发改为 CAS（`compareAndSet`），防止并发下多次触发。
- **`MemoryService.drainPending`**：RENAME 操作包裹 try-catch，竞争失败时静默跳过（正常场景）。
- **`ReflectionWorker`**：确保反思后调用 `UserProfileService.upsertAttribute` 更新画像。
- **`EvictionPolicyManager`**：在淘汰逻辑中补充对 `type=reflection` 的保护过滤。

---

## 架构数据流

```
会话消息 (addMessage)
    │
    ▼ 超出 MAX_HISTORY(20)
pending buffer (Redis List)
    │
    ▼ 积累 ≥ batch-size(5) → 原子 drain
SummarizationRequestEvent
    │
    ▼ MemorySummarizer (Async LLM)
ConsolidationRequestEvent (SummaryResult)
    │
    ▼ MemoryConsolidator → VectorStore (type=summary)
    │  count ≥ reflection-threshold(10) ?
    ▼ YES: ReflectionRequestEvent
ReflectionWorker (Async LLM) → VectorStore (type=profile, importance=1.0)
                              → UserProfileService (Redis Hash)
                                    │
                                    ▼ AgentOrchestrator 注入系统 prompt

EvictionPolicyManager (@Scheduled 每天 3AM)
    └─ 淘汰低 importance / 过期 summary，跳过 profile/reflection 类型
```

---

## 新增文件清单

| 文件 | 类型 | 说明 |
|---|---|---|
| `memory/SummarizationRequestEvent.java` | Event | 摘要请求事件 |
| `memory/ConsolidationRequestEvent.java` | Event | 整合请求事件 |
| `memory/ReflectionRequestEvent.java` | Event | 反思请求事件 |
| `memory/SummaryResult.java` | Record | 摘要结果值对象 |
| `memory/MemorySummarizer.java` | Service | 异步 LLM 摘要压缩 |
| `memory/MemoryConsolidator.java` | Service | 摘要持久化 + 反思触发 |
| `memory/ReflectionWorker.java` | Service | 异步 LLM 反思提炼 |
| `memory/EvictionPolicyManager.java` | Service | 定时淘汰过期条目 |
| `memory/UserProfileService.java` | Service | Redis 用户画像存储 |
| `*Test.java` (6 个) | Test | 各组件单元测试 |
