package com.dawn.ai.agent.trace;

import com.dawn.ai.exception.MaxStepsExceededException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * ThreadLocal-based request-scoped step collector.
 * Bridges ToolExecutionAspect and AgentOrchestrator without touching tool classes.
 *
 * Lifecycle per request:
 *   AgentOrchestrator.doChat() calls init() → AOP records steps → collect() → clear()
 *
 * RETRIEVED_QUERIES tracks already-searched queries within one request to prevent
 * duplicate RAG calls that would waste tokens.
 */
@Slf4j
public class StepCollector {

    private static final ThreadLocal<List<AgentStep>> STEPS =
            ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<AtomicInteger> COUNTER =
            ThreadLocal.withInitial(() -> new AtomicInteger(0));
    private static final ThreadLocal<Integer> MAX_STEPS = new ThreadLocal<>();
    /** package-private for test accessibility */
    static final ThreadLocal<Set<String>> RETRIEVED_QUERIES =
            ThreadLocal.withInitial(HashSet::new);
    /** Optional listener for real-time step events in streaming mode. */
    private static final ThreadLocal<Consumer<AgentStep>> STEP_LISTENER = new ThreadLocal<>();

    /** Call at the start of each request to reset state from any previous run. */
    public static void init(Integer maxSteps) {
        init(maxSteps, null);
    }

    /**
     * Overload for streaming mode: registers a listener that is invoked immediately
     * on each {@link #record(AgentStep)} call, enabling real-time step events.
     *
     * @param listener optional; pass {@code null} for non-streaming requests
     */
    public static void init(Integer maxSteps, Consumer<AgentStep> listener) {
        STEPS.get().clear();
        COUNTER.get().set(0);
        MAX_STEPS.set(maxSteps);
        RETRIEVED_QUERIES.get().clear();
        STEP_LISTENER.set(listener);
    }

    /** Called by ToolExecutionAspect after each tool invocation. */
    public static void record(AgentStep step) {
        STEPS.get().add(step);
        Consumer<AgentStep> listener = STEP_LISTENER.get();
        if (listener != null) {
            listener.accept(step);
        }
    }

    /** Returns the next monotonically increasing step number for the current request. */
    public static int getAndIncreaseStepNumber() {
        int next = COUNTER.get().incrementAndGet();
        Integer max = MAX_STEPS.get();
        if (max == null) {
            // ThreadLocal not initialized on this thread — likely a Reactor worker thread in streaming mode.
            // Log a warning so the issue is visible, but allow the tool call to proceed.
            log.warn("[StepCollector] MAX_STEPS not set on thread '{}' — StepCollector.init() was called on a different thread. Tool call will proceed without step-limit enforcement.",
                    Thread.currentThread().getName());
            return next;
        }
        if (next > max) {
            log.error("Exceeded Max Steps: {}", next);
            throw new MaxStepsExceededException("Exceeded Max Steps: " + max);
        }
        return next;
    }

    /** Returns a snapshot of all recorded steps for the current request. */
    public static List<AgentStep> collect() {
        return new ArrayList<>(STEPS.get());
    }

    /**
     * Returns true if the given rewritten query has already been searched this request.
     * Used by KnowledgeSearchTool to prevent duplicate RAG calls.
     */
    public static boolean isQueryRetrieved(String query) {
        return RETRIEVED_QUERIES.get().contains(query);
    }

    /**
     * Marks a rewritten query as already searched for this request.
     * Call immediately before executing a RAG retrieval.
     */
    public static void markQueryRetrieved(String query) {
        RETRIEVED_QUERIES.get().add(query);
    }

    /** Must be called in a finally block to prevent ThreadLocal memory leaks. */
    public static void clear() {
        STEPS.remove();
        COUNTER.remove();
        MAX_STEPS.remove();
        RETRIEVED_QUERIES.remove();
        STEP_LISTENER.remove();
    }
}
