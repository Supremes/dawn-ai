# SSE 改造 Code Review TODO（2026-04-22）

## 0. 评估结论

总体：**主链路功能完整，可演示，但工程化收尾未达计划要求**。

| 维度 | 状态 | 说明 |
|---|---|---|
| Controller `POST /api/v1/chat/stream` | ✅ | 与 solution §5.1 一致，复用 `ChatRequest` |
| `ChatService.streamChat` + 专用线程池 | ✅ | `chatStreamExecutor` 已分离，避免污染 servlet 线程 |
| `ChatStreamEvent` DTO + 工厂方法 | ✅ | 含 connected/plan/plan_thinking/thinking/step/token/done/error，比计划更丰富 |
| `AgentOrchestrator.streamChat` | ✅ | plan→token→done 主链路已通；额外补了 reasoning 流 |
| `StepCollector` listener + 跨线程修复 | ✅ | 通过 Micrometer `ContextRegistry` + Reactor `enableAutomaticContextPropagation()` 解决 ThreadLocal 跨 Reactor 工作线程失效；设计文档补全 |
| 前端 SSE 解析 + 增量渲染 | ✅ | `app.js` 用 `fetch+ReadableStream` 解析 SSE，含 plan/thinking/token/step/done/error 全事件类型 |
| 包结构重构 | ✅ | `agent.{orchestration,planning,registry,trace}` 拆分清晰；ArchUnit 测试守护 |
| 单元/集成测试 | ❌ | **完全缺失**：无 `ChatServiceStreamTest`、无 `AgentOrchestratorStreamTest`、无 `ChatControllerSseTest` |
| 客户端断开传播 | ❌ | **未实现** `emitter.onCompletion`，浏览器关闭后 LLM 继续生成、token 浪费 |
| 心跳 / 反代防空闲 | ❌ | 未实现 heartbeat 事件，长 thinking 期可能被中间代理切断 |
| 超时 / 异常错误码映射 | ⚠️ | 缺 `LLMProviderException → LLM_PROVIDER_ERROR`，缺 `retryable` 字段 |
| Stream 监控指标 | ❌ | 无 `ai.chat.stream.duration / active / requests.total / error.total` |

---

## 1. P0 — Critical（无）

当前未发现会导致数据错误或安全问题的阻塞级缺陷。

---

## 2. P1 — High（必须在合并前修复）

### P1-1 客户端断开未传播，LLM 继续烧 token
**位置**: `src/main/java/com/dawn/ai/service/ChatService.java:109-114`

`emitter` 仅注册了 `onTimeout` / `onError`，**没有 `onCompletion`**；
`AgentOrchestrator.streamChat` 中 `chatClient.prompt()...stream().chatResponse().blockLast()` 不会响应取消信号，浏览器关闭页面后：

1. Tomcat 检测到客户端断开，触发 `onError`，但 handler 只 `log.warn`，没有调用 `emitter.complete()` 或中断异步任务
2. 异步线程仍在 `blockLast()` 等待 LLM 全部 chunk
3. 模型继续生成 → 浪费 token、占用 chatStreamExecutor 线程

**修复方案**：
- 增加 `AtomicBoolean cancelled` 由 `onCompletion/onError/onTimeout` 共同 set
- `AgentOrchestrator.streamChat` 改为接收一个 `BooleanSupplier isCancelled`（或在 `sink` 处统一感知发送失败），在 `doOnNext` 中 `Flux.takeWhile(c -> !cancelled.get())`
- 或者：`Disposable subscription = ...subscribe(...)`，在 `onCompletion/onError` 中 `subscription.dispose()`，避免使用 `blockLast()`

**验证**: `curl -N ... | head -1` 后检查日志 LLM 不再继续输出 chunk。

---

### P1-2 SSE 全链路零测试
**位置**: 整个 SSE 链路

计划文档 §7 / solution §7 明确要求至少三类测试：
- `ChatControllerSseTest`：MockMvc 校验 `text/event-stream` Content-Type 和异步处理
- `AgentOrchestratorStreamTest`：mock `chatClient.stream().chatResponse()` 返回多 chunk，断言事件顺序 `plan → token* → done`
- `StepCollectorTest` listener 触发 + clear() 不泄漏

当前仓库 `src/test/java` 下：
- `AgentOrchestratorTest` 仅覆盖同步 `chat()`
- `StepCollectorTest` 未补 listener 用例
- 无任何 `Sse*` / `Stream*` 测试类

**修复方案**：
- 至少补 `AgentOrchestratorStreamTest`：用 `Flux.just(chunk1, chunk2)` 替身验证事件顺序与累积 answer
- `StepCollectorTest` 补 `init(maxSteps, listener) → record(step) → listener 被回调` 用例
- `ChatControllerSseTest` 用 MockMvc + `MockHttpServletResponse` 抓取 SSE body

---

### P1-3 流内异常码映射不完整
**位置**: `src/main/java/com/dawn/ai/agent/orchestration/AgentOrchestrator.java:248-259`

solution §5.5 列出错误映射：
```
MaxStepsExceededException     → MAX_STEPS_EXCEEDED
AiConfigurationException      → AI_NOT_CONFIGURED
LLMProviderException          → LLM_PROVIDER_ERROR   ← 当前缺失，被吞成 INTERNAL_ERROR
其他                            → INTERNAL_ERROR
```

且 `cause` 仅下钻一层：`Throwable cause = e.getCause() != null ? e.getCause() : e;`
对于 Reactor 链路常见的 `ReactiveException → Wrapped → 真实异常` 多层包装，会丢失业务码。

**修复方案**：
- 抽 `mapErrorCode(Throwable)`：`Throwable root = NestedExceptionUtils.getMostSpecificCause(e)` 后再 `instanceof` 判断
- 加入 `LLMProviderException → LLM_PROVIDER_ERROR`
- 错误事件 payload 补 `retryable` 字段（见 solution §4.4）

---

### P1-4 同步路径 system prompt 与流式不一致
**位置**: `src/main/java/com/dawn/ai/agent/orchestration/AgentOrchestrator.java:101-103` vs `194-197`

- 同步 `doChat`：`baseSystemPrompt + formatPlan + maxSteps 限制`
- 流式 `streamChat`：额外 `formatPlanEnforcement(plan)` 强制按计划执行

同样的 LLM、同样的工具，**两个入口产出策略不同**会让用户在 sync↔stream 之间体验差异巨大，并让回归测试结论无法迁移。

**修复方案**：
- 抽公共 `buildSystemPrompt(plan)`，两个入口共享
- 决定 plan-enforcement 是否对同步链路也生效（建议生效）

---

## 3. P2 — Medium（应在本 PR 内或紧随其后修复）

### P2-1 缺少 heartbeat，反代会切断长 thinking
**位置**: `ChatService.streamChat`

solution §4.3 与 §9.3 都把 heartbeat 列为必备；当前未实现。Nginx 默认 `proxy_read_timeout 60s`，长链路 reasoning 阶段超过 60s 不发任何字节会被切断。

**修复方案**：
- 在 `chatStreamExecutor.execute(...)` 启动一个 ScheduledFuture，每 15s `emitter.send(SseEmitter.event().comment("ping"))` 或自定义 `heartbeat` 事件
- 流结束时 cancel scheduled task

---

### P2-2 `done` 事件重复回传 steps
**位置**: `ChatStreamEvent.done()` 与前端 `finaliseAssistantMessage`

step 已通过 `step` 事件实时推送；`done.data.steps` 又把全部步骤再传一次；前端拿 `meta.steps` 重建 trace 面板，浪费带宽且可能与已有 DOM 重复。

**修复方案**（二选一）：
- `done` 事件不再携带 `steps` 字段，前端 finalise 直接用已收集的 step DOM
- 或 `done.steps` 仅保留 stepNumber 列表用于校验，不带 input/output

---

### P2-3 `chatStreamExecutor` 用 `CallerRunsPolicy` 会污染 servlet 线程
**位置**: `src/main/java/com/dawn/ai/config/AgentConfig.java:36-48`

`ThreadPoolExecutor(8, 32, queue=200, CallerRunsPolicy)`：当并发超过 32 + 200 时，**回退到调用线程执行**——而调用线程正是 Tomcat 的 servlet 工作线程。SSE 是长连接（120s 超时），一旦回退会直接锁死 servlet 线程，违反 solution §5.3 "不要直接使用公共 ForkJoinPool" 的初衷。

**修复方案**：
- 改 `AbortPolicy` + Controller 捕获返回 503 `Service Unavailable`
- 或 `DiscardPolicy` + 业务可观测告警；
- 同时把 queue capacity 调小（如 64），避免任务在队列里堆积超过 timeout

---

### P2-4 `AiSyncResponseCapture` ThreadLocal 跨请求残留风险
**位置**: `src/main/java/com/dawn/ai/config/AiSyncResponseCapture.java` + `AiConfig.java:81`

`AiConfig` 的 RestClient 拦截器每次都 `set` 响应体；`TaskPlanner.extractReasoningFromCapturedResponse` 在 finally 里 `clear()`，但**只有在走兜底路径时才清理**。如果 plan 阶段未走兜底，TL 会保留上一轮请求的响应；下一轮请求若 plan 走兜底，可能误读上一轮残留。

虽然实测概率低（必须连续两次都使用 RestClient + reasoning 缺失），但属于"全局 ThreadLocal 没生命周期"的典型反模式。

**修复方案**：
- 在 `AiConfig` 的拦截器 finally / 在 `TaskPlanner.plan` finally 中无条件 `AiSyncResponseCapture.clear()`
- 或改为方法返回值显式传递，移除 ThreadLocal

---

### P2-5 `streamChat` 注释顺序和实际不一致
**位置**: `AgentOrchestrator.java:159-160` 与 `ChatController.java:43-44` 与 `ChatStreamEvent.java:18`

三处事件顺序描述各异：
- ChatStreamEvent: `connected → plan_thinking* → plan? → thinking* → step* → token* → done | error`
- AgentOrchestrator: `plan_thinking* → plan → thinking* → step* → token* → done | error`
- ChatController: `connected → plan? → step* → token* → done | error`（缺 thinking/plan_thinking）

**修复方案**：以 ChatStreamEvent 顶部为权威定义，`AgentOrchestrator` 和 `ChatController` 注释统一引用。

---

### P2-6 `error` 事件后未触发 `emitter.complete()`
**位置**: `AgentOrchestrator.streamChat` catch 块（247-262）

异常路径仅 `sink.accept(error事件)`，未 complete emitter；外层 `ChatService.streamChat` lambda 进入 `try { ... } catch (Exception e)` 但因 `streamChat` 自己 catch 了所有异常，外层 catch 不会触发。emitter 最终靠 `emitter.complete()` 在 `try` 外的 lambda 末尾收口——错误也会到达，但顺序依赖隐式行为，容易在重构时断裂。

**修复方案**：`streamChat` 在 catch 后 `throw new SseStreamCompletedException(code)`，让外层显式区分正常/异常 complete 路径。

---

### P2-7 `seq` 字段在多线程 sink 下顺序不严格
**位置**: `ChatService.sendEvent` 与 `AgentOrchestrator.streamChat`

`AtomicInteger seqCounter` 保证唯一递增，但 step 事件由 Reactor 工作线程触发、token 由 doOnNext 线程触发，两路并发调用 `sink.accept` 时 SSE write 顺序与 seq 数值可能存在颠倒：客户端按 seq 排序时会感觉乱序。

**修复方案**：
- 在 `ChatService.sendEvent` 上加 `synchronized`（emitter.send 本身线程不安全）
- 或用单线程 `Channel` 串行化所有事件后再 emit

---

## 4. P3 — Low（可在后续清理）

### P3-1 缩进/格式不一致
- `ChatStreamEvent.java:58-65` planThinking 用 8 空格缩进，文件其他位置 4 空格
- `AgentOrchestrator.java:202-204` log 使用 tab 缩进，与全局空格风格冲突
- `AiConfig.java:101-112` openAiWebClientBuilder 整段 16 空格缩进

**修复方案**：统一项目 .editorconfig + IDE Reformat。

---

### P3-2 `recordTokenUsage` 在流式路径未调用
**位置**: `AgentOrchestrator.streamChat`

`inputTokenCounter` / `outputTokenCounter` 仅在同步 `doChat` 调用；流式不计入 `ai.token.input/output` 指标，监控会随流式占比上升而失真。

**修复方案**：在 `chatResponse()` 流的最后一个非空 chunk 提取 `getMetadata().getUsage()`，存在时 `recordTokenUsage(chunk)`（最佳努力，参考 solution §5.6）。

---

### P3-3 缺 stream 维度监控指标
计划 §8 + solution §5.6 列出：
- `ai.chat.stream.requests.total{status}`
- `ai.chat.stream.duration`
- `ai.chat.stream.active.connections`
- `ai.chat.stream.error.total{type}`

当前完全缺失。

---

### P3-4 `simpleChat` 不再走 `aiAvailabilityChecker.ensureConfigured` 之外的限制
非 SSE 改动残留，但 ChatController `/simple` 接口不限速、不走 memory，未来要明确该接口的对外可见性。

---

### P3-5 `chatStreamExecutor` `destroyMethod="shutdown"` 未 await
应用关闭时 `shutdown()` 立刻返回；正在执行的 SSE 请求会被强制中断且无 graceful 等待。

**修复方案**：自定义 `@PreDestroy` 调 `shutdownNow()` + `awaitTermination(10, SECONDS)`。

---

## 5. 改动总结（一句话版）

> 本分支在 SSE 主链路、跨线程 ThreadLocal 修复、前端增量渲染、包结构重组上完成度高，但**收尾工程性（测试、断连传播、监控、心跳、错误码、prompt 一致性）整体缺失**，按当前状态合并会留下"功能可演示、生产不可控"的债务。

---

## 6. 推荐落地顺序

1. **本周 P1 全部修完**（断连传播 + 测试 + 错误码 + prompt 对齐）
2. **下周 P2-1 / P2-3 / P2-7**（heartbeat + 线程池策略 + 事件顺序）
3. **再下周 P2/P3 收尾 + 监控指标接 Grafana**
