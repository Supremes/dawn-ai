# SSE 流式响应改造方案（2026-04-15）

## 1. 当前现状

当前聊天链路是典型的同步阻塞模型：

- `ChatController.chat()` 调用 `ChatService.chat()`，返回完整 `ChatResponse`
- `ChatService.chat()` 调用 `AgentOrchestrator.chat()`
- `AgentOrchestrator.doChat()` 最终执行 `chatClient.prompt()...call().chatResponse()`，等待大模型完整生成后一次性返回
- 前端 `static/js/app.js` 使用 `fetch('/api/v1/chat')`，必须等整个 JSON 完成后才能渲染
- `StepCollector` 只在请求结束时 `collect()`，虽然会记录工具调用步骤，但不能在运行中实时向前端推送

这意味着：

1. 首 token 延迟高，用户体感是“卡住后突然返回一大段文本”
2. 工具调用过程、规划过程、最终答案无法实时展示
3. 当前异常处理只覆盖同步 JSON 响应，没有定义流式接口的错误事件契约

## 2. 目标与边界

### 2.1 目标

- 保留现有 `POST /api/v1/chat`，不破坏同步调用方
- 新增 SSE 流式接口，支持逐步返回规划、工具步骤、文本增量、完成事件
- 前端能够边收边渲染，而不是等待完整 JSON
- 保持现有会话记忆、RAG 工具、AOP 步骤采集逻辑可复用

### 2.2 非目标

- 本阶段不做全量 WebFlux 化
- 本阶段不替换 JPA 为 R2DBC，不替换 `RedisTemplate` 为 Reactive Redis
- 本阶段不追求“所有依赖都非阻塞”；重点是先把用户响应模式从“整包返回”改成“渐进返回”

## 3. 方案选择

### 方案 A：直接迁移到 WebFlux + `Flux<ServerSentEvent<?>>`

优点：

- 代码形态更纯粹，天然适合流式返回
- `Flux`/`Mono` 组合能力更强

问题：

- 当前项目仍以 Spring MVC 为主，已使用 `spring-boot-starter-web`
- JPA、`RedisTemplate`、多数业务逻辑仍是阻塞式，切到 WebFlux 也无法自动变成真正非阻塞
- Web 层改成响应式后，数据访问层仍阻塞，线程模型收益有限，但改造面会显著扩大
- 一次性引入 WebFlux、改测试方式、改异常处理、改前端协议，风险偏高

结论：不建议作为第一步。

### 方案 B：保留 Spring MVC，新增 SSE 异步输出（推荐）

核心思路：

- 保留现有 Servlet/MVC 栈
- 新增 `text/event-stream` 接口
- 使用 `SseEmitter` 作为输出通道
- 由 `ChatClient.stream()` 提供模型增量输出，再桥接到 `SseEmitter`
- 工具调用步骤仍沿用 `ToolExecutionAspect + StepCollector`，但增加“实时监听器”能力

优点：

- 对现有项目侵入最小
- 无需整体迁移到 WebFlux
- 现有同步接口、异常处理、记忆模块可以保留
- 适合当前“先把用户体验做对”的目标

代价：

- 仍然需要线程池管理和超时控制
- 背压能力不如端到端响应式方案精细
- 某些依赖仍然是阻塞调用，只是响应不再整包等待

结论：这是当前仓库最稳妥的落地路径。

## 4. 推荐的接口设计

### 4.1 生产接口

建议使用：

```http
POST /api/v1/chat/stream
Content-Type: application/json
Accept: text/event-stream
```

请求体继续复用现有 `ChatRequest`，不要把 message 塞进 query string。

原因：

- 与现有 `POST /api/v1/chat` 契约一致
- 避免长文本、换行、多语言字符放入 URL 的长度和编码问题
- 前端当前已经使用 `fetch`，保留 JSON body 最自然

### 4.2 调试接口（可选）

为了便于 `curl` 或浏览器 `EventSource` 手工验证，可以额外提供一个只用于调试的 GET 版本：

```http
GET /api/v1/chat/stream?message=...&sessionId=...
```

但这个 GET 接口不建议作为主接口。

### 4.3 SSE 事件类型

建议统一输出如下事件：

1. `connected`
2. `plan`
3. `step`
4. `token`
5. `done`
6. `error`
7. `heartbeat`

推荐事件载荷结构：

```json
{
  "event": "token",
  "sessionId": "session-abc",
  "seq": 12,
  "timestamp": "2026-04-15T23:00:00Z",
  "data": {
    "content": "你好"
  }
}
```

### 4.4 各事件建议载荷

`connected`

```json
{
  "sessionId": "session-abc",
  "streamId": "stream-xyz"
}
```

`plan`

```json
{
  "steps": [
    { "step": 1, "action": "knowledgeSearchTool", "reason": "先查知识库" },
    { "step": 2, "action": "calculatorTool", "reason": "再计算年费" }
  ],
  "summary": "步骤1: knowledgeSearchTool → 步骤2: calculatorTool"
}
```

`step`

直接复用 `AgentStep`：

```json
{
  "stepNumber": 1,
  "toolName": "KnowledgeSearchTool",
  "toolInput": "Dawn AI 月费",
  "toolOutput": "月费 99 元",
  "durationMs": 122
}
```

`token`

```json
{
  "content": "年费",
  "accumulatedLength": 24
}
```

`done`

建议与现有 `ChatResponse` 对齐：

```json
{
  "sessionId": "session-abc",
  "answer": "Dawn AI 年费为 1188 元。",
  "durationMs": 1842,
  "model": "qwen-plus",
  "steps": [ ... ],
  "planSummary": "步骤1: knowledgeSearchTool → 步骤2: calculatorTool",
  "totalSteps": 2
}
```

`error`

```json
{
  "code": "MAX_STEPS_EXCEEDED",
  "message": "Exceeded Max Steps: 10",
  "retryable": false
}
```

## 5. 后端改造建议

## 5.1 控制器层

新增流式接口，保留同步接口不动：

```java
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
    return chatService.streamChat(request);
}
```

这里推荐返回 `SseEmitter`，而不是第一步就把 Controller 改成 `Flux<ServerSentEvent<?>>`。

## 5.2 服务层

`ChatService` 新增流式方法：

```java
public SseEmitter streamChat(ChatRequest request)
```

职责：

- 生成或复用 `sessionId`
- 创建 `SseEmitter`
- 在专用线程池中启动 orchestrator 流式调用
- 订阅事件并写入 SSE
- 处理超时、取消订阅、异常收尾

建议增加独立线程池，例如：

```yaml
app:
  ai:
    stream:
      timeout: 120s
      heartbeat-interval: 15s
      executor:
        core-size: 8
        max-size: 32
        queue-capacity: 200
```

## 5.3 编排层

`AgentOrchestrator` 新增流式编排方法：

```java
public void streamChat(String sessionId, String userMessage, Consumer<ChatStreamEvent> sink)
```

推荐流程：

1. `StepCollector.init(maxSteps, stepListener)`
2. 计算计划 `resolvePlan(userMessage)`
3. 先推送 `plan` 事件
4. 执行 `chatClient.prompt()...toolNames(...).stream().chatResponse()`
5. 对每个 chunk：
   - 读取增量文本
   - 推送 `token` 事件
   - 累积最终答案
6. AOP 记录到工具步骤后，`StepCollector` 立即回调 `stepListener`，推送 `step` 事件
7. `Flux` 完成后：
   - 持久化 user/assistant 到 Redis
   - 推送 `done` 事件
8. 任意异常：推送 `error` 事件
9. `finally` 里 `StepCollector.clear()`

建议的伪代码如下：

```java
public void streamChat(String sessionId, String userMessage, Consumer<ChatStreamEvent> sink) {
    long start = System.currentTimeMillis();
    StringBuilder answer = new StringBuilder();

    StepCollector.init(maxSteps, step -> sink.accept(ChatStreamEvent.step(sessionId, step)));
    try {
        List<PlanStep> plan = resolvePlan(userMessage);
        sink.accept(ChatStreamEvent.plan(sessionId, plan, formatPlanSummary(plan)));

        chatClient.prompt()
                .system(buildSystemPrompt(plan))
                .messages(buildHistory(sessionId))
                .user(userMessage)
                .toolNames(toolRegistry.getNames())
                .stream()
                .chatResponse()
                .doOnNext(chunk -> {
                    String delta = extractText(chunk);
                    if (delta != null && !delta.isBlank()) {
                        answer.append(delta);
                        sink.accept(ChatStreamEvent.token(sessionId, delta, answer.length()));
                    }
                })
                .doOnComplete(() -> {
                    memoryService.addMessage(sessionId, "user", userMessage);
                    memoryService.addMessage(sessionId, "assistant", answer.toString());
                    sink.accept(ChatStreamEvent.done(sessionId, answer.toString(), StepCollector.collect(), plan,
                            System.currentTimeMillis() - start, model));
                })
                .doOnError(error -> sink.accept(ChatStreamEvent.error(sessionId, mapErrorCode(error), error.getMessage())))
                .blockLast();
    }
    finally {
        StepCollector.clear();
    }
}
```

说明：

- 这里的 `blockLast()` 只发生在专用异步线程里，不阻塞 servlet 请求线程
- 这仍然不是“全栈非阻塞”，但已经实现了对浏览器的渐进式输出

## 5.4 StepCollector 改造点

当前 `StepCollector` 只能“事后 collect”。

建议扩展成既能收集，也能实时派发：

```java
private static final ThreadLocal<Consumer<AgentStep>> STEP_LISTENER = new ThreadLocal<>();

public static void init(Integer maxSteps, Consumer<AgentStep> listener) {
    STEPS.get().clear();
    COUNTER.get().set(0);
    MAX_STEPS.set(maxSteps);
    STEP_LISTENER.set(listener);
    RETRIEVED_QUERIES.get().clear();
}

public static void record(AgentStep step) {
    STEPS.get().add(step);
    Consumer<AgentStep> listener = STEP_LISTENER.get();
    if (listener != null) {
        listener.accept(step);
    }
}
```

这样做的好处是：

- `ToolExecutionAspect` 不需要感知 SSE 传输层
- 同步模式与流式模式共用同一套步骤采集逻辑
- 单元测试更容易做

## 5.5 异常处理

当前 `ApiExceptionHandler` 只适合同步 JSON。SSE 需要单独策略：

- 在进入流之前抛出的异常：继续沿用现有 `ApiExceptionHandler`，返回标准 JSON 错误
- 在流进行中发生的异常：不要再尝试切换成 HTTP 错误码，直接发送 `error` 事件并结束流

建议错误码映射：

- `MaxStepsExceededException` -> `MAX_STEPS_EXCEEDED`
- `AiConfigurationException` -> `AI_NOT_CONFIGURED`
- `LLMProviderException` -> `LLM_PROVIDER_ERROR`
- 其他异常 -> `INTERNAL_ERROR`

## 5.6 指标与可观测性

建议新增：

- `ai.chat.stream.duration`：单次流式请求总耗时
- `ai.chat.stream.active`：当前活跃流数
- `ai.chat.stream.error.total{type=...}`：流式错误计数
- 继续复用现有 `ai.tool.duration` 与 `ai.tool.calls.total`

关于 token 用量：

- `ChatClient.stream()` 支持 `chatResponse()`，理论上可以读取 `ChatResponse` metadata
- 但 usage 是否在每个 chunk、最后一个 chunk、还是完全没有，取决于模型提供方和 Spring AI/OpenAI 适配实现
- 因此建议把流式 token 指标定义为“最佳努力”：只有在最终 chunk 拿到 usage 时才上报；拿不到时不要伪造

## 6. 前端改造建议

当前前端是：

- 发送 `POST /api/v1/chat`
- `await res.json()`
- 完整返回后一次性渲染

建议改为两种模式并存：

1. 默认走流式
2. 保留同步降级开关

### 6.1 推荐实现方式

由于主接口建议使用 `POST`，前端不适合使用原生 `EventSource`。推荐继续使用 `fetch`，但按 SSE 协议解析响应流。

前端职责：

- 发送 `POST /api/v1/chat/stream`
- 逐行解析 `event:` / `data:`
- `token` 事件持续更新当前 assistant 气泡内容
- `step` 事件插入步骤面板
- `done` 事件补齐 meta 信息
- `error` 事件中止 loading 状态并显示错误

### 6.2 为什么不推荐前端强依赖 EventSource

- `EventSource` 天生只支持 GET
- 当前聊天消息来自 textarea，多行文本和较长输入不适合塞进 URL
- 保持 POST body 可以与现有 `ChatRequest` 保持一致，前后端都更稳

## 7. 测试方案

建议最少覆盖以下测试：

1. `StepCollectorTest`
   - 验证 listener 会在 `record()` 时被调用
   - 验证 `clear()` 后 ThreadLocal 不泄漏

2. `AgentOrchestratorStreamTest`
   - mock `chatClient.stream().chatResponse()` 返回多个 chunk
   - 验证事件顺序：`plan -> token* -> done`
   - 验证工具调用时会插入 `step`
   - 验证异常时会发送 `error`

3. `ChatControllerSseTest`
   - 验证接口 `Content-Type` 为 `text/event-stream`
   - 验证请求进入异步处理
   - 验证流里至少包含 `event: done` 或 `event: error`

4. 前端手工验证

```bash
curl -N \
  -H 'Accept: text/event-stream' \
  -H 'Content-Type: application/json' \
  -d '{"message":"Dawn AI 月费是多少，顺便算年费","sessionId":"demo-sse-1"}' \
  http://localhost:8080/api/v1/chat/stream
```

## 8. 上线时的工程注意事项

1. 反向代理必须关闭缓冲，否则 SSE 会被缓存后整包吐出

Nginx 示例：

```nginx
location /api/v1/chat/stream {
    proxy_pass http://app;
    proxy_http_version 1.1;
    proxy_set_header Connection '';
    proxy_buffering off;
    proxy_cache off;
    chunked_transfer_encoding on;
    add_header X-Accel-Buffering no;
}
```

2. 需要处理浏览器断开连接

- `SseEmitter.onCompletion`
- `SseEmitter.onTimeout`
- `SseEmitter.onError`

一旦断开，要及时取消底层订阅，避免模型继续生成浪费 token。

3. `step` 事件的语义是“工具调用完成”，不是“工具调用开始”

因为当前 AOP 是在 `pjp.proceed()` 之后才记录 `AgentStep`，所以前端看到 step 时，说明工具结果已经可用。

4. 会话持久化应放在 `done` 前后统一收口

- 用户消息只在请求真正进入生成后再落库
- assistant 消息使用最终聚合结果落库，避免存半截答案

## 9. 推荐落地顺序

### Phase 1：后端可用

- 新增 `ChatStreamEvent` DTO
- `StepCollector` 增加 listener 能力
- `AgentOrchestrator.streamChat()`
- `ChatService.streamChat()`
- `ChatController` 新增 `/stream`
- 单元测试补齐

### Phase 2：前端接入

- `app.js` 增加 SSE 解析器
- assistant 气泡改成增量更新
- step 面板改成边收边展示
- 增加流式开关和降级逻辑

### Phase 3：观测与收尾

- 增加 stream metrics
- 增加 heartbeat
- 校验代理层无缓冲
- 手工验证取消连接、超时、异常场景

## 10. 最终建议

对当前仓库，最合适的路线不是“为了 SSE 先把项目全量改成 WebFlux”，而是：

1. 继续保留现有 Spring MVC + 同步接口
2. 新增一个基于 `SseEmitter` 的流式接口
3. 用 `ChatClient.stream().chatResponse()` 提供模型增量
4. 用 `StepCollector` listener 把工具调用实时转成 `step` 事件
5. 前端用 `fetch` 解析 `text/event-stream`，逐步渲染 token/step/done

这条路径改造面最小、风险最低，并且与项目当前的阻塞型 JPA/Redis/Controller 现实完全兼容。