package com.dawn.ai.agent;

import com.dawn.ai.exception.MaxStepsExceededException;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class StepCollector {

    private static final ThreadLocal<List<AgentStep>> STEPS =
            ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<AtomicInteger> COUNTER =
            ThreadLocal.withInitial(() -> new AtomicInteger(0));
    private static final ThreadLocal<Integer> MAX_STEPS = new ThreadLocal<>();

    /** Call at the start of each request to reset state from any previous run. */
    public static void init(Integer maxSteps) {
        STEPS.get().clear();
        COUNTER.get().set(0);
        MAX_STEPS.set(maxSteps);
    }

    /** Called by ToolExecutionAspect after each tool invocation. */
    public static void record(AgentStep step) {
        STEPS.get().add(step);
    }

    /** Returns the next monotonically increasing step number for the current request. */
    public static int getAndIncreaseStepNumber() {
        int next = COUNTER.get().incrementAndGet();
        if (next >  MAX_STEPS.get()) {
            log.error("Exceeded Max Steps: {}", next);
            throw new MaxStepsExceededException("Exceeded Max Steps: " + MAX_STEPS.get().toString());
        }

        return next;
    }

    /** Returns a snapshot of all recorded steps for the current request. */
    public static List<AgentStep> collect() {
        return new ArrayList<>(STEPS.get());
    }

    /** Must be called in a finally block to prevent ThreadLocal memory leaks. */
    public static void clear() {
        STEPS.remove();
        COUNTER.remove();
        MAX_STEPS.remove();
    }
}
