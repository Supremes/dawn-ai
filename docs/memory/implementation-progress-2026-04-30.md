# Memory P0 Engineering — Implementation Progress

> 记录日期：2026-04-30
> 分支：`feature/memory-management`
> 计划文件：`docs/superpowers/plans/2026-04-30-memory-p0-engineering.md`

---

## 已完成（本次 session）

| Task | 描述 | Commit |
|------|------|--------|
| Task 1 | `@EnableScheduling` + `@EnableAsync` 加到 `DawnAiApplication` | `209bd6b` |
| Task 2 | `app.memory.*` 配置键写入 `application.yml` | `8407817` |
| Task 3 | `MemoryService` 重写：Redis failsafe（ConcurrentHashMap 兜底）+ 待摘要队列（pending buffer）+ `SummarizationRequestEvent` | `be26b12` |
| Task 3 fix | `MemoryServiceTest.setUp()` 补调 `initMetrics()` 避免 NPE | `6b18e49` |
| Task 4 | `MemorySummarizer`（`@EventListener @Async`）+ `SummaryResult` record + `ConsolidationRequestEvent` record | `0647ec6` |
| Task 4 fix | `getOrDefault` 防 null + 精确测试断言 | `38765a6` |
| Task 5 | `MemoryConsolidator`（写入 VectorStore，达阈值触发 Reflection）+ `ReflectionRequestEvent` | `421e4d9` |

### 已创建文件

```
src/main/java/com/dawn/ai/memory/
  SummarizationRequestEvent.java   # 事件：弹出消息批次 → 触发摘要
  SummaryResult.java               # Record：sessionId / text / importanceScore / createdAt
  ConsolidationRequestEvent.java   # 事件：摘要结果 → 触发持久化
  ReflectionRequestEvent.java      # 事件：达阈值 → 触发 Reflection
  MemorySummarizer.java            # 异步 LLM 压缩，降级为原文
  MemoryConsolidator.java          # 写入 PGVector，计数触发 Reflection

src/main/java/com/dawn/ai/service/
  MemoryService.java               # 重写：failsafe + pending buffer

src/test/java/com/dawn/ai/service/
  MemoryServiceTest.java           # 4 个单元测试

src/test/java/com/dawn/ai/memory/
  MemorySummarizerTest.java        # 2 个单元测试
  MemoryConsolidatorTest.java      # 3 个单元测试
```

### 事件总线数据流

```
MemoryService.addMessage()
  → [pending batch >= 5] SummarizationRequestEvent
      → MemorySummarizer.onSummarizationRequest()  [@Async]
          → LLM 压缩（失败降级原文）
          → ConsolidationRequestEvent
              → MemoryConsolidator.onConsolidationRequest()  [@Async]
                  → VectorStore.add()
                  → [consolidation count >= threshold] ReflectionRequestEvent
                      → ReflectionWorker.onReflectionRequest()  [未实现，待 Task 8]
```

---

## 剩余待办

### Task 6 — User Profile / Hard Memory

**文件：**
- Create: `src/main/java/com/dawn/ai/memory/UserProfileService.java`
- Modify: `src/main/java/com/dawn/ai/agent/orchestration/AgentOrchestrator.java`
- Create: `src/test/java/com/dawn/ai/memory/UserProfileServiceTest.java`

**核心逻辑：**
- `UserProfileService`：Redis Hash (`ai:profile:{userId}`)，提供 `upsertAttribute / getProfile / formatForSystemPrompt`
- `AgentOrchestrator`：移除 `@RequiredArgsConstructor`，显式构造器注入 `UserProfileService`，`buildSystemPrompt(plan, sessionId)` 前缀注入用户画像

---

### Task 7 — Decay / Eviction

**文件：**
- Create: `src/main/java/com/dawn/ai/memory/EvictionPolicyManager.java`
- Create: `src/test/java/com/dawn/ai/memory/EvictionPolicyManagerTest.java`

**核心逻辑：**
- `@Scheduled(cron = "${app.memory.eviction.cron:0 0 3 * * ?}")`
- 查询 VectorStore（probe query），过滤 `importance < 0.1 AND createdAt < now - 180d`
- 调用 `vectorStore.delete(ids)`

---

### Task 8 — Reflection

**文件：**
- Create: `src/main/java/com/dawn/ai/memory/ReflectionWorker.java`
- Create: `src/test/java/com/dawn/ai/memory/ReflectionWorkerTest.java`

**核心逻辑：**
- `@EventListener @Async` 监听 `ReflectionRequestEvent`
- 查询 sessionId 的历史 summary（metadata filter `sessionId`）
- 不足阈值则跳过；足够则调用 LLM 提炼高层偏好
- 写入 VectorStore，`type=reflection`，`importance=0.9`

---

### Task 9 — 全量测试验证

运行所有测试，验证 `memory` 包文件齐全，做最终 commit。

---

## 架构备注

- **循环依赖规避**：所有 memory 组件通过 `ApplicationEventPublisher` 解耦，无直接 Bean 依赖链
- **Redis failsafe 范围**：仅覆盖 `MemoryService`（working memory 层）；`UserProfileService` 需独立补充 failsafe
- **VectorStore metadata 约定**：所有写入文档必须包含 `type / sessionId / importance / createdAt / lastAccessedAt` 五个字段
- **sessionId = userId**：当前系统无独立用户认证，以 `sessionId` 代替 `userId` 存储 profile，后续接入 Auth 时需迁移
