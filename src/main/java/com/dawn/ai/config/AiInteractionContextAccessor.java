package com.dawn.ai.config;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * Bridges Micrometer context propagation with {@link AiInteractionContext} so the
 * immutable request snapshot follows reactive pipelines across worker threads
 * (e.g. Reactor's {@code boundedElastic} scheduler used by Spring AI tool callbacks).
 *
 * <p>Registered in {@link AgentConfig#enableReactorContextPropagation()}.
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
