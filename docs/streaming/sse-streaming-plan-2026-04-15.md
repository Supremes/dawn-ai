# Dawn AI 流式 SSE 改造方案

## 1. 背景与目标

当前项目的主对话链路是阻塞式请求-响应模型：

- `ChatController` 的 `POST /api/v1/chat` 返回完整 `ChatResponse`
- `ChatService` 调用 `AgentOrchestrator.chat()`，等待完整结果后一次性返回
- `AgentOrchestrator` 内部通过 `chatClient.prompt().call().chatResponse()` 同步拿到最终回答
- 前端 `app.js` 使用 `fetch()`，只有在整个请求结束后才展示回答

这套模型实现简单，但有三个明显问题：

1. 首 token 延迟高，用户只能等待完整答案返回
2. Agent 的计划、工具调用、最终生成过程不可见
3. 一旦 LLM 响应较慢，前端缺乏中间态反馈，体验接近“卡住”而不是“思考中”

本方案目标：

1. 在不破坏现有同步接口的前提下新增流式能力
2. 让前端实时接收 `plan`、`step`、`token`、`done` 等事件
3. 复用当前已有的 `StepCollector`、`ToolExecutionAspect`、`MemoryService`、`AgentOrchestrator` 体系
4. 控制改造范围，优先采用兼容当前 `spring-boot-starter-web` 的渐进式方案

---

## 2. 现状分析

### 2.1 当前阻塞点

当前链路的关键阻塞发生在以下位置：

- `ChatController.chat()` 只能在 `ChatService.chat()` 完成后返回
- `ChatService.chat()` 只能在 `AgentOrchestrator.chat()` 完整执行后返回
- `AgentOrchestrator.doChat()` 中的 `chatClient.prompt()...call().chatResponse()` 会阻塞直到模型返回完整结果

这意味着即使底层模型支持 streaming，当前接口形态也无法把中间结果透传给浏览器。

### 2.2 当前可复用能力

项目并非从零开始，下面这些能力可以直接复用：

1. `TaskPlanner` 已经可以在正式生成前产出 plan，适合映射为 `plan` 事件
2. `ToolExecutionAspect` 已经拦截工具调用，适合映射为 `step` 事件
3. `StepCollector` 已有请求级状态管理和 `maxSteps` 保护，适合扩展为“采集 + 推送”双用途
4. `MemoryService` 已负责会话记忆，可在流结束后统一落库
5. 静态前端已具备消息列表、步骤展示、会话 ID 管理，只需改造请求方式和渲染策略

### 2.3 当前技术栈约束

`pom.xml` 当前是：

- `spring-boot-starter-web`
- 没有显式引入 `spring-boot-starter-webflux`

因此有两条技术路线：

1. 继续使用 Spring MVC，采用 `SseEmitter`
2. 切换到 WebFlux，采用 `Flux<ServerSentEvent<?>>`

从当前仓库状态看，推荐优先走方案一。

---

## 3. 推荐方案

## 3.1 结论

推荐采用：**Spring MVC + `SseEmitter` + 事件总线式流转上下文**。

理由：

1. 与现有 `spring-boot-starter-web` 完全兼容，不需要把整条 HTTP 栈切到 WebFlux
2. Controller、异常处理、现有测试风格可以基本保持不变
3. 前端改造重点集中在消费 SSE 和增量渲染，不需要同步改后端整体编程模型
4. 风险更低，适合作为第一阶段交付

如果后续目标不仅是 SSE，而是端到端响应式编排、R2DBC、Reactive Redis、Reactive Security，再评估升级到 WebFlux。

---

## 4. 目标架构

### 4.1 接口保持双轨

保留现有同步接口：

- `POST /api/v1/chat` 保持原样，返回完整 JSON

新增流式接口：

- `GET /api/v1/chat/stream?message=...&sessionId=...`

说明：

1. 保留同步接口，避免影响现有调用方和测试用例
2. 新接口仅服务浏览器实时对话和后续控制台调试
3. 等流式模式稳定后，前端默认切换到 SSE，同步接口作为回退路径

### 4.2 事件模型

建议统一使用如下 SSE 事件类型：

#### `connected`

用途：SSE 建链成功，前端可切换到“已连接”状态。

示例：

```json
{
  "sessionId": "session-abc",
  "timestamp": "2026-04-15T10:00:00Z"
}
```

#### `plan`

用途：在 LLM 正式回答前把规划结果发给前端。

示例：

```json
{
  "steps": [
    {"step": 1, "action": "knowledgeSearchTool", "reason": "先查知识库"},
    {"step": 2, "action": "calculatorTool", "reason": "再做计算"}
  ],
  "summary": "步骤1: knowledgeSearchTool -> 步骤2: calculatorTool"
}
```

#### `step`

用途：每次工具调用完成后实时推送。

示例：

```json
{
  "stepNumber": 1,
  "toolName": "KnowledgeSearchTool",
  "toolInput": "Dawn AI 月费",
  "toolOutput": "月费 99 元",
  "durationMs": 86
}
```

#### `token`

用途：流式推送模型输出片段。

示例：

```json
{
  "content": "根据知识库，",
  "index": 12
}
```

#### `done`

用途：完整结束信号，包含最终元数据。

示例：

```json
{
  "sessionId": "session-abc",
  "answer": "根据知识库，Dawn AI 月费为 99 元，年费为 1188 元。",
  "model": "qwen-plus",
  "durationMs": 2140,
  "totalSteps": 2,
  "planSummary": "步骤1: knowledgeSearchTool -> 步骤2: calculatorTool"
}
```

#### `error`

用途：以业务可读方式结束流。

示例：

```json
{
  "code": "LLM_TIMEOUT",
  "message": "模型响应超时，请稍后重试。"
}
```

事件顺序建议：

`connected -> plan? -> step* -> token* -> done | error`

---

## 5. 后端设计

### 5.1 新增流式上下文对象

建议新增一个请求级上下文，例如：

- `ChatStreamContext`
- `ChatStreamEvent`
- `ChatStreamEventPublisher`

职责拆分建议：

1. `ChatStreamContext` 负责持有 `SseEmitter`、答案缓冲区、sessionId、开始时间
2. `ChatStreamEventPublisher` 负责把领域事件编码成 SSE 发送出去
3. `AgentOrchestrator` 专注编排，不直接拼接 SSE 细节

核心接口示意：

```java
public interface ChatStreamEventPublisher {
    void connected(String sessionId);
    void plan(List<PlanStep> plan);
    void step(AgentStep step);
    void token(String token);
    void done(StreamDonePayload payload);
    void error(String code, String message);
}
```

这样可以避免把 `SseEmitter.send(...)` 散落到 Controller、Service、AOP 多处。

### 5.2 Controller 设计

新增接口建议：

```java
@GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamChat(@RequestParam String message,
                             @RequestParam(required = false) String sessionId) {
    return chatService.streamChat(message, sessionId);
}
```

说明：

1. SSE 的浏览器原生消费工具是 `EventSource`，它天然适合 GET
2. 如果后续必须传复杂 JSON，可以新增 `POST /api/v1/chat/stream`，由前端用 `fetch()` 读取 `ReadableStream`
3. 当前需求只需要 `message` 与 `sessionId`，先用 GET 即可快速落地

### 5.3 Service 设计

`ChatService` 增加新方法：

```java
public SseEmitter streamChat(String message, String sessionId)
```

其职责：

1. 参数兜底，生成 sessionId
2. 创建 `SseEmitter`
3. 异步触发 `agentOrchestrator.streamChat(...)`
4. 统一处理 emitter 生命周期：`onCompletion`、`onTimeout`、`onError`

建议显式配置专用线程池，例如：

- `chatStreamExecutor`

不要直接使用公共 `ForkJoinPool`，否则高并发下线程资源不可控。

### 5.4 Orchestrator 设计

新增方法建议：

```java
public void streamChat(String sessionId,
                       String userMessage,
                       ChatStreamEventPublisher publisher)
```

内部流程建议：

1. `StepCollector.init(maxSteps)`
2. 解析 plan，并立刻发送 `plan` 事件
3. 构建 system prompt 与 history
4. 调用 `chatClient.prompt()...stream().content()` 订阅 token
5. 工具调用完成时推送 `step` 事件
6. token 全部结束后，汇总完整 answer
7. 落库 `MemoryService`
8. 发送 `done`
9. finally 中 `StepCollector.clear()`

关键点：

1. **不要**边收到 token 边写 Redis 历史，应该在流完成后一次性写入最终答案
2. `plan` 与 `step` 都属于“结构化事件”，前端用于展示 agent 过程
3. `token` 只用于逐字渲染回答正文

### 5.5 StepCollector 改造建议

当前 `StepCollector` 只有“采集”职责。为了支持实时 `step` 事件，建议做最小扩展：

方案 A，推荐：

1. 保留现有 `StepCollector.collect()` 语义
2. 额外增加请求级监听器，例如 `StepListener`
3. `record(step)` 时除了写入 ThreadLocal，也同步通知监听器

接口示意：

```java
public interface StepListener {
    void onStep(AgentStep step);
}
```

`StepCollector` 新增：

```java
public static void init(Integer maxSteps, @Nullable StepListener listener)
```

这样 `ToolExecutionAspect` 无需理解 SSE，它仍只调用 `StepCollector.record(step)`，解耦程度最高。

方案 B：

直接在 `ToolExecutionAspect` 中注入事件发布器。

不推荐原因：

1. AOP 层会耦合 HTTP 推送概念
2. 同步接口与流式接口会共享同一个 Aspect，分支逻辑容易变脏
3. 不利于单元测试

### 5.6 Token 流式输出

`AgentOrchestrator` 当前使用：

```java
chatClient.prompt()...call().chatResponse()
```

流式版本建议切换为：

```java
chatClient.prompt()...stream().content()
```

处理建议：

1. 每个 token 到达时 `publisher.token(token)`
2. 同时 append 到 `StringBuilder finalAnswer`
3. 流完成后生成完整文本用于落库和 `done` 事件

注意：

1. token 事件必须允许空片段过滤，避免前端无意义刷新
2. 如果底层模型在工具调用和最终回答之间存在多阶段输出，需要验证 Spring AI 对 tool calling + stream 的实际行为
3. 如果 Spring AI 当前组合场景下对 `toolNames()` + `stream()` 支持不稳定，则采用“两阶段流式”降级方案

### 5.7 两阶段流式降级方案

如果验证后发现当前 Spring AI 版本在“工具调用 + streaming”组合下不稳定，建议使用下面的兼容方案：

阶段 1：

1. 保持工具编排仍走当前同步 `call()`
2. 在 plan 和 step 层面先做到实时可见

阶段 2：

1. 工具执行结束后，最后一次纯文本总结调用改为 `.stream().content()`
2. 前端仍可感知“Agent 先规划 / 调工具 / 再流式回答”

这个方案虽然不是全链路纯 streaming，但足够解决当前最大痛点，并且工程风险更低。

---

## 6. 前端设计

### 6.1 请求方式调整

当前 `app.js` 使用：

```javascript
fetch('/api/v1/chat', { method: 'POST', ... })
```

建议新增流式路径：

```javascript
const url = new URL('/api/v1/chat/stream', window.location.origin);
url.searchParams.set('message', message);
url.searchParams.set('sessionId', state.sessionId);
const source = new EventSource(url);
```

### 6.2 渲染策略

建议把 assistant 消息拆成两个区域：

1. `answerBuffer`，用于逐步追加 token
2. `tracePanel`，用于展示 `plan` 与 `step`

前端行为建议：

1. 用户发送后立刻插入一条空的 assistant 占位消息
2. 收到 `token` 时只更新该占位消息正文
3. 收到 `plan` 时更新“执行计划”区域
4. 收到 `step` 时动态追加工具调用明细
5. 收到 `done` 时再补充 model、duration、totalSteps 等 meta 信息

### 6.3 回退策略

前端应保留同步回退：

1. `EventSource` 不可用时，回退到当前 `fetch` 模式
2. SSE 中途断开且未收到 `done` 时，提示用户重试
3. 后端返回 `error` 事件时关闭连接并展示业务错误

---

## 7. 测试方案

### 7.1 单元测试

新增或补强以下测试：

1. `ChatServiceTest`
   - 创建 `SseEmitter` 后是否异步触发 orchestrator
   - 异常时是否正确 `completeWithError`

2. `AgentOrchestratorTest`
   - plan 事件是否先于 done 发送
   - token 是否被累积成最终 answer
   - 完成后是否写入 `MemoryService`

3. `StepCollectorTest`
   - listener 是否在 `record(step)` 时触发
   - `clear()` 是否清理 listener，避免 ThreadLocal 泄漏

### 7.2 Web 层测试

如果采用 MVC + `SseEmitter`：

1. 使用 `MockMvc` 触发 `/api/v1/chat/stream`
2. 断言响应类型为 `text/event-stream`
3. 验证事件名和 payload 结构

如果将来切到 WebFlux，再引入 `WebTestClient` / `StepVerifier`。

### 7.3 手工验证

建议至少覆盖：

```bash
curl -N "http://localhost:8080/api/v1/chat/stream?message=你好&sessionId=test-1"
```

验证点：

1. 是否按序收到 `connected/plan/token/done`
2. 工具调用场景下是否能收到 `step`
3. 超时、限流、配置缺失时是否收到 `error`

---

## 8. 监控与治理

建议新增指标：

1. `ai.chat.stream.requests.total{status}`
2. `ai.chat.stream.duration`
3. `ai.chat.stream.active.connections`
4. `ai.chat.stream.tokens.output`
5. 复用已有 `ai.tool.duration`、`ai.tool.calls.total`

还需要补三类治理项：

1. 超时：`SseEmitter` 设置合理 timeout，例如 60s 或 120s
2. 断连：浏览器主动断开后尽快终止后端流，避免 LLM 仍持续生成
3. 背压：SSE 本身无真正背压能力，高频 token 推送时可按字符数或时间窗口合并发送

token 合并建议：

1. 每 20 到 50 ms flush 一次，或
2. 每累计 16 到 32 个字符 flush 一次

这样能明显降低前端重绘和网络包数量。

---

## 9. 分阶段实施计划

### Phase 1：先打通最小 SSE 主链路

目标：快速产出可演示版本。

内容：

1. 新增 `/api/v1/chat/stream`
2. 新增 `ChatService.streamChat()`
3. `AgentOrchestrator.streamChat()` 支持 `token` + `done`
4. 前端使用 `EventSource` 逐字展示答案

此阶段先不要求 `plan` 和 `step` 实时推送，也可以先不做 token 聚合优化。

### Phase 2：接入 Agent 过程可视化

内容：

1. `plan` 事件推送
2. `StepCollector` listener 机制
3. `step` 事件推送
4. 前端 trace panel 增量展示

### Phase 3：补齐稳定性与观测性

内容：

1. 超时、断连、异常事件统一处理
2. token flush 优化
3. 监控指标落地
4. 测试覆盖补齐

---

## 10. 最终建议

从当前项目状态出发，最合理的落地顺序是：

1. **先用 `SseEmitter` 把 SSE 跑通**，不急着切 WebFlux
2. **先实现 `token + done`，再逐步补 `plan + step`**
3. **通过 `StepCollector` listener 扩展实时步骤事件**，不要让 AOP 直接感知 SSE
4. **保留现有 `POST /api/v1/chat` 作为稳定回退路径**

简化后的推荐改造面如下：

- `ChatController`：新增 `GET /stream`
- `ChatService`：新增 `streamChat()`
- `AgentOrchestrator`：新增 `streamChat()`
- `StepCollector`：新增 listener 能力
- 新增 `ChatStreamContext` / `ChatStreamEventPublisher`
- `app.js`：新增 EventSource 消费和增量渲染

这套方案的优点是：

1. 对现有代码侵入可控
2. 能较快交付用户可感知的体验提升
3. 能为后续 Agent 过程可视化和 WebFlux 演进保留空间

如果要直接进入实现，建议按 Phase 1 到 Phase 3 顺序推进，而不是一次性把响应式栈、SSE 协议、前端重构和监控全部同时改完。