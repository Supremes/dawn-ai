package com.dawn.ai.sse;

import java.util.function.Consumer;

/**
 * 流式请求期间持有当前 SSE sink 的 ThreadLocal 持有者。
 *
 * <p>由 {@code AgentOrchestrator.streamChat} 在入口处 {@link #set} 当前请求的 sink，
 * 在 finally 中 {@link #clear}。供 {@code DispatchSubAgentTool} 在 sub-agent 派发时
 * 读取，进而为 sub-agent 构造 sub-step 进度心跳监听器。
 *
 * <p>跨线程：sub-agent 在 worker 线程读取 sink 时，本类的 ThreadLocal 在 worker
 * 上无值——但 sink 引用已通过 closure 传给 progress listener，listener 调用 sink
 * 时直接命中已捕获引用，与 ThreadLocal 是否传播无关。
 */
public final class StreamSinkHolder {

    private static final ThreadLocal<Consumer<ChatStreamEvent>> CURRENT = new ThreadLocal<>();

    private StreamSinkHolder() {}

    public static void set(Consumer<ChatStreamEvent> sink) {
        CURRENT.set(sink);
    }

    public static Consumer<ChatStreamEvent> get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
