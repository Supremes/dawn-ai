package com.dawn.ai.agent.trace;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * 把 Micrometer context propagation 和 {@link StepCollector} 桥接起来。
 *
 * <p>开启 Reactor 自动上下文传播（{@code Hooks.enableAutomaticContextPropagation()}）后，
 * Reactor 会在订阅时把当前的 {@link StepCollectorContext} 捕获进响应式管道的 context，
 * 然后在每个 operator 执行前通过 {@link #setValue(StepCollectorContext)} 恢复它，
 * 无论该 operator 跑在哪个 Reactor worker 线程上。
 *
 * <p>因为 {@link StepCollectorContext} 是共享的可变对象（不是副本），
 * 同一请求上的所有线程共用同一套计数器、步骤列表和去重集合，
 * 从而解决了裸 ThreadLocal 固有的跨线程隔离问题。
 *
 * <p>在 {@link com.dawn.ai.config.AgentConfig} 中注册。
 */
public class StepCollectorContextAccessor implements ThreadLocalAccessor<StepCollectorContext> {

    /** 注册表 key，在所有已注册的 accessor 中必须唯一。 */
    public static final String KEY = "dawn.ai.stepCollector";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public StepCollectorContext getValue() {
        return StepCollector.currentContext();
    }

    @Override
    public void setValue(StepCollectorContext value) {
        StepCollector.setContext(value);
    }

    /** 离开传播作用域时由 Reactor 调用，用于清理 ThreadLocal。 */
    @Override
    public void setValue() {
        StepCollector.clearContext();
    }
}
