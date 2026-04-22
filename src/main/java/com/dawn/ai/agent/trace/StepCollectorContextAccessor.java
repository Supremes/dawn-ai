package com.dawn.ai.agent.trace;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * Bridges Micrometer context propagation with {@link StepCollector}.
 *
 * <p>When Reactor's automatic context propagation is enabled (via
 * {@code Hooks.enableAutomaticContextPropagation()}), Reactor captures the current
 * {@link StepCollectorContext} into the reactive pipeline's context at subscription time,
 * then restores it via {@link #setValue(StepCollectorContext)} before every operator
 * executes — regardless of which Reactor worker thread runs it.
 *
 * <p>Because {@link StepCollectorContext} is a shared mutable object (not a copy),
 * all threads operating on the same request share the same counters, step list,
 * and dedup set, fixing the cross-thread isolation problem inherent in raw ThreadLocal.
 *
 * <p>Registered in {@link com.dawn.ai.config.AgentConfig}.
 */
public class StepCollectorContextAccessor implements ThreadLocalAccessor<StepCollectorContext> {

    /** Registry key — must be unique across all registered accessors. */
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

    /** Called by Reactor to clear the ThreadLocal when leaving a propagated scope. */
    @Override
    public void setValue() {
        StepCollector.clearContext();
    }
}
