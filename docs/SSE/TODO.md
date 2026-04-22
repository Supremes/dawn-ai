# SSE Streaming TODO

> 关联文档：[code-review-todo-2026-04-22.md](./code-review-todo-2026-04-22.md)
> 当前阶段：**Phase 3 收尾（稳定性、可观测性、测试）**

---

## Phase A — 阻塞合并（P1，必须修）

### A1. 客户端断开传播
- [ ] `ChatService.streamChat` 注册 `emitter.onCompletion` / 在 `onError` / `onTimeout` 中 set 取消标志
- [ ] `AgentOrchestrator.streamChat` 改用 `subscribe()` + `Disposable`，或在 `doOnNext` 里 `takeUntil(cancelled)`，废弃 `blockLast()` 后无法响应取消的写法
- [ ] 验证 `curl -N ... | head -1` 触发 client close 后日志显示 LLM chunk 立刻停止

**位置**: `ChatService.java:109-114`, `AgentOrchestrator.java:206-233`

### A2. SSE 测试补齐
- [ ] `AgentOrchestratorStreamTest` — mock `chatClient.stream().chatResponse()` 返回多 chunk，断言事件顺序 `plan → token* → done` 与累积 answer 一致
- [ ] `StepCollectorTest` 增加 listener 触发用例 + `clear()` 后 ThreadLocal 不泄漏
- [ ] `ChatControllerSseTest` — MockMvc 校验 `Content-Type=text/event-stream`、异步处理、至少包含 `done` 或 `error`
- [ ] CI 流水线打开测试覆盖统计

**位置**: `src/test/java/com/dawn/ai/agent/orchestration/`, `src/test/java/com/dawn/ai/controller/`

### A3. 错误码映射统一
- [ ] 抽 `mapErrorCode(Throwable)`，使用 `NestedExceptionUtils.getMostSpecificCause` 而不是 `e.getCause()` 单层下钻
- [ ] 加入 `LLMProviderException → LLM_PROVIDER_ERROR`
- [ ] `ChatStreamEvent.error` payload 增补 `retryable: boolean`

**位置**: `AgentOrchestrator.java:248-262`, `ChatStreamEvent.java:112-122`

### A4. 同步 / 流式 system prompt 对齐
- [ ] 抽公共 `buildSystemPrompt(plan)`，统一 `formatPlan + formatPlanEnforcement + maxSteps` 文案
- [ ] 同步 `doChat` 也强制按 plan 执行（默认开），避免 sync/stream 行为分裂

**位置**: `AgentOrchestrator.java:101-103` vs `194-197`

---

## Phase B — 工程化收尾（P2）

### B1. Heartbeat
- [ ] 启动 SSE 时注册 `ScheduledFuture`，每 15s 推送 `:ping` comment 或 `heartbeat` 事件
- [ ] 流结束 / cancel / error 时 cancel scheduled task
- [ ] 反代配置文档（Nginx `proxy_read_timeout 0; proxy_buffering off;`）写入 README

**位置**: `ChatService.java`

### B2. `done` 事件去重
- [ ] 决策：`done` 是否仍需重传 `steps` 全量？建议改为 stepNumber 列表 + checksum
- [ ] 前端 `finaliseAssistantMessage` 不再用 `meta.steps` 重建 trace 面板

**位置**: `ChatStreamEvent.java:94-110`, `static/js/app.js:304-352`

### B3. `chatStreamExecutor` 拒绝策略修正
- [ ] `CallerRunsPolicy` → `AbortPolicy` + Controller 侧统一 503
- [ ] queue capacity 200 → 64，避免长任务在队列里堆积超过 timeout
- [ ] 增加 `ai.chat.stream.queue.size` Gauge

**位置**: `AgentConfig.java:36-48`

### B4. 事件顺序文档统一
- [ ] 三处事件序列描述统一为 `connected → plan_thinking* → plan? → thinking* → step* → token* → done | error`
- [ ] `ChatController` / `AgentOrchestrator` 注释引用 `ChatStreamEvent` 顶部权威定义

### B5. SSE write 串行化
- [ ] `ChatService.sendEvent` 加 `synchronized` 或改 `BlockingQueue + 单线程 emitter`
- [ ] 验证：高频 token + 并发 step 事件下 SSE 序号严格单调

**位置**: `ChatService.java:132-143`

### B6. 移除 `AiSyncResponseCapture` 跨请求残留风险
- [ ] `AiConfig` 拦截器 finally 无条件 `clear()`
- [ ] 或重构为方法返回值显式传递，移除全局 ThreadLocal

**位置**: `AiSyncResponseCapture.java`, `AiConfig.java:81`

### B7. `error` 事件后显式 complete
- [ ] `AgentOrchestrator.streamChat` catch 块在推送 error 后 `throw new SseStreamCompletedException`
- [ ] 外层 lambda 显式区分正常 / 异常 complete 路径

---

## Phase C — 可观测性 & 体验（P3）

### C1. Stream 监控指标
- [ ] `ai.chat.stream.requests.total{status}` Counter
- [ ] `ai.chat.stream.duration` Timer
- [ ] `ai.chat.stream.active.connections` Gauge
- [ ] `ai.chat.stream.error.total{type}` Counter
- [ ] 接 Grafana dashboard

### C2. Stream 路径 token usage 上报
- [ ] `chatResponse()` 流最后一个 chunk 提取 `Usage`，存在时 `recordTokenUsage`
- [ ] 拿不到 usage 时不要伪造（最佳努力）

### C3. 代码格式 / 缩进统一
- [ ] `.editorconfig` 全项目化
- [ ] `ChatStreamEvent.java:58-65` / `AgentOrchestrator.java:202-204` / `AiConfig.java:101-112` 重新格式化
- [ ] CI 加 `spotless:check` 或等效检查

### C4. Broken pipe 日志降噪
- [ ] `ChatService.sendEvent` 检测 IOException(Broken pipe) 与 `IllegalStateException(has already completed)` → DEBUG 级
- [ ] 关联 A1，断开后立即 complete 自然消除大部分噪音

### C5. `chatStreamExecutor` 优雅关闭
- [ ] 自定义 `@PreDestroy`：`shutdownNow()` + `awaitTermination(10, SECONDS)`
- [ ] 捕获中断状态，避免应用 hang

---

## Completed Tasks

- [x] Add `thinking` SSE event and frontend thought panel for main stream reasoning
- [x] Add `plan_thinking` SSE event for planner reasoning content
- [x] Implement fallback `AiSyncResponseCapture` for planner `reasoning_content` extraction
- [x] Add `WebClient` streaming request/response logging via `ExchangeFilterFunction`
- [x] Add chunk-level stream logs in `AgentOrchestrator`
- [x] Fix `KnowledgeSearchTool.Request` optional parameters schema
- [x] Pretty-print full AI HTTP response bodies at DEBUG level
- [x] Remove truncation from AI sync response content logs
- [x] Refactor `TaskPlanner.plan()` to return `PlannerResult` with reasoning
- [x] Update unit tests for `PlannerResult` refactor
- [x] Fix Docker image build after test-compile issues
- [x] Cross-thread StepCollector via Micrometer context propagation（commit 2bad650）
- [x] Prevent NPE when tool runs on Reactor worker thread（commit dae5dc2）

---

## 备选 / 远期

- [ ] Trim or summarize very long `plan_thinking` reasoning before rendering
- [ ] Implement backpressure/queueing for high-frequency `thinking` events
- [ ] Cache partial `reasoning_content` between sessions for similar queries
- [ ] WebFlux 全栈化评估（数据访问层全部改造完成后再启动）
