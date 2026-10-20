package com.dawn.ai.config;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * 把 Micrometer 的上下文传播和 {@link AiInteractionContext} 接起来，让不可变的请求快照
 * 能跟着响应式管线跨 worker 线程流转
 * （比如 Spring AI tool callback 用到的 Reactor {@code boundedElastic} 调度器）。
 *
 * <p>在 {@link AgentConfig#enableReactorContextPropagation()} 里注册。
 */
public class AiInteractionContextAccessor implements ThreadLocalAccessor<AiInteractionContext.State> {

    public static final String KEY = "dawn.ai.interactionSession";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public AiInteractionContext.State getValue() {
        return AiInteractionContext.snapshot();
    }

    @Override
    public void setValue(AiInteractionContext.State value) {
        AiInteractionContext.restore(value);
    }

    @Override
    public void setValue() {
        AiInteractionContext.clear();
    }
}
