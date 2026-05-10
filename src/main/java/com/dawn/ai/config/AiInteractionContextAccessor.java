package com.dawn.ai.config;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * Bridges Micrometer context propagation with {@link AiInteractionContext} so the
 * sessionId follows reactive pipelines across worker threads (e.g. Reactor's
 * {@code boundedElastic} scheduler used by Spring AI tool callbacks). Without this,
 * embedding/tool calls running on a different thread would log to the
 * {@code "no-session"} bucket and never surface in the per-session UI.
 *
 * <p>Registered in {@link AgentConfig#enableReactorContextPropagation()}.
 */
public class AiInteractionContextAccessor implements ThreadLocalAccessor<String> {

    public static final String KEY = "dawn.ai.interactionSession";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public String getValue() {
        return AiInteractionContext.getSessionId();
    }

    @Override
    public void setValue(String value) {
        AiInteractionContext.setSessionId(value);
    }

    @Override
    public void setValue() {
        AiInteractionContext.clear();
    }
}
