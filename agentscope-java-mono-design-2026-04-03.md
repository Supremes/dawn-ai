# agentscope-java 的 Mono 设计详解

> 来源：[agentscope-ai/agentscope-java](https://github.com/agentscope-ai/agentscope-java)  
> 日期：2026-04-03

---

## 1. 整体定位

`agentscope-java` 用了 **Project Reactor**（Spring WebFlux 同款响应式库）的 `Mono<T>` 和 `Flux<T>` 作为核心异步抽象，而不是传统的 `Future`/`CompletableFuture`。

```
Mono<Msg>   ——  代表 agent 调用的「单次异步结果」（0 或 1 个值）
Flux<Event> ——  代表 streaming 模式下的「事件流」（0-N 个值）
```

---

## 2. 接口层设计

### `CallableAgent`（核心接口）

```java
// CallableAgent.java
Mono<Msg> call(List<Msg> msgs);
Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel);
Mono<Msg> call(List<Msg> msgs, JsonNode schema);
```

所有 `call()` 变体最终都代理到 `call(List<Msg>)`。`Mono<Msg>` 是冷流（lazy），**声明时不执行，subscribe 时才触发**。

### `StreamableAgent`（流式接口）

```java
// StreamableAgent.java
Flux<Event> stream(List<Msg> msgs, StreamOptions options);
```

streaming 模式返回 `Flux<Event>`，可实时推送 reasoning/tool_call/chunk 等事件。

### `ObservableAgent`（观察接口）

```java
Mono<Void> observe(Msg msg);   // 只接收，不回复
Mono<Void> observe(List<Msg> msgs);
```

---

## 3. `AgentBase` 中 Mono 的关键用法

### 3.1 `Mono.using()` —— 资源生命周期管理

```java
// AgentBase.java:325
@Override
public final Mono<Msg> call(List<Msg> msgs) {
    return Mono.using(
        this::acquireExecution,   // 1. 获取资源（设置 running=true，注册优雅关闭）
        resource -> ...           // 2. 主逻辑链
            notifyPreCall(msgs)
                .flatMap(this::doCall)
                .flatMap(this::notifyPostCall)
                .onErrorResume(createErrorHandler(msgs.toArray(new Msg[0]))),
        this::releaseExecution,   // 3. 无论成功/失败/取消都执行（类似 try-finally）
        true
    );
}
```

`Mono.using()` 保证了：
- 并发保护：`running` 标志防止同一 agent 实例并发调用
- 优雅关闭：通过 `GracefulShutdownManager` 注册/注销请求
- **finally 语义**：`releaseExecution` 在任何终止信号（onComplete/onError/cancel）后都执行

### 3.2 Hook 链 —— `flatMap` 串联

```java
// notifyPreCall: 顺序执行所有 Hook，每个 hook 可修改输入消息
private Mono<List<Msg>> notifyPreCall(List<Msg> msgs) {
    PreCallEvent event = new PreCallEvent(this, msgs);
    Mono<PreCallEvent> result = Mono.just(event);
    for (Hook hook : getSortedHooks()) {
        result = result.flatMap(hook::onEvent);  // 串联：前一个完成后执行下一个
    }
    return result.map(PreCallEvent::getInputMessages);
}
```

这是 Reactor 中常见的**顺序 flatMap 链**，等价于同步代码的 `for` 循环但是非阻塞。

### 3.3 协作式中断 —— `Mono.defer()` + 错误信号传播

```java
// checkInterruptedAsync: 在 Mono 链的检查点插入
protected Mono<Void> checkInterruptedAsync() {
    return Mono.defer(() ->
        interruptFlag.get()
            ? Mono.error(new InterruptedException("Agent execution interrupted"))
            : Mono.empty()
    );
}
```

子类在 Mono 链的关键节点调用它：

```java
// ReActAgent 的中断检查点示例（框架文档）
return checkInterruptedAsync()
    .then(reasoning())
    .flatMap(result -> checkInterruptedAsync().thenReturn(result))
    .flatMap(result -> executeTools(result));
```

`AgentBase.call()` 在末尾统一捕获 `InterruptedException`：

```java
.onErrorResume(error -> {
    if (error instanceof InterruptedException) {
        return handleInterrupt(context, msgs);
    }
    return notifyError(error).then(Mono.error(error));
});
```

### 3.4 Streaming 的实现 —— `Flux.create()` 桥接 Mono

```java
// createEventStream: Mono → Flux 的桥接
private Flux<Event> createEventStream(...) {
    return Flux.deferContextual(ctxView ->
        Flux.<Event>create(sink -> {
            StreamingHook streamingHook = new StreamingHook(sink, options);
            addHook(streamingHook);   // 临时 Hook，把 Agent 内部事件推入 sink

            Mono.defer(() -> callSupplier.get())
                .contextWrite(context -> context.putAll(ctxView))
                .doFinally(signal -> hooks.remove(streamingHook))  // 用完即删
                .subscribe(
                    finalMsg -> { sink.next(agentResultEvent); sink.complete(); },
                    sink::error
                );
        }, FluxSink.OverflowStrategy.BUFFER)
        .publishOn(Schedulers.boundedElastic())
    );
}
```

---

## 4. 整体设计思路总结

```
call(msgs)
    │
    ▼
Mono.using(acquire, mainChain, release)
    │
    ├── notifyPreCall()  ← flatMap Hook 链（可修改输入）
    │
    ├── doCall()         ← 子类实现（如 ReActAgent，包含 LLM 调用 + Tool 循环）
    │   └── 在 Mono 链中插入 checkInterruptedAsync() 检查点
    │
    ├── notifyPostCall() ← flatMap Hook 链 + 广播给 MsgHub 订阅者
    │
    └── onErrorResume()  ← 统一错误处理（中断走 handleInterrupt，其他走 notifyError）
```

| 设计点 | 使用的 Reactor API | 目的 |
|---|---|---|
| 资源生命周期 | `Mono.using()` | finally 语义，防泄漏 |
| Hook 顺序执行 | `.flatMap()` 链 | 同步语义，非阻塞 |
| 协作式中断 | `Mono.defer()` + `Mono.error()` | 在 Mono 链内传播异常 |
| Mono→Flux 桥接 | `Flux.create()` + `FluxSink` | call 结果转 streaming 事件流 |
| 多 subscriber 广播 | `Flux.fromIterable().flatMap()` | MsgHub 并发通知 |
| 延迟求值 | `Mono.defer()` | 确保每次 subscribe 重新执行 |
