package com.dawn.ai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ThreadLocal-based request-scoped step collector.
 * Bridges ToolExecutionAspect and AgentOrchestrator without touching tool classes.
 *
 * Lifecycle per request:
 *   AgentOrchestrator.doChat() calls init() → AOP records steps → collect() → clear()
 */
public class StepCollector {

    private static final ThreadLocal<List<AgentStep>> STEPS =
            ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<AtomicInteger> COUNTER =
            ThreadLocal.withInitial(() -> new AtomicInteger(0));

    /** Call at the start of each request to reset state from any previous run. */
    public static void init() {
        STEPS.get().clear();
        COUNTER.get().set(0);
    }

    /** Called by ToolExecutionAspect after each tool invocation. */
    public static void record(AgentStep step) {
        STEPS.get().add(step);
    }

    /** Returns the next monotonically increasing step number for the current request. */
    public static int nextStepNumber() {
        return COUNTER.get().incrementAndGet();
    }

    /** Returns a snapshot of all recorded steps for the current request. */
    public static List<AgentStep> collect() {
        return new ArrayList<>(STEPS.get());
    }

    /** Must be called in a finally block to prevent ThreadLocal memory leaks. */
    public static void clear() {
        STEPS.remove();
        COUNTER.remove();
    }
}
