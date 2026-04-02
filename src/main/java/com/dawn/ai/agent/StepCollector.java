package com.dawn.ai.agent;

import java.util.HashSet;
import java.util.Set;

/**
 * Minimal stub retained for RETRIEVED_QUERIES dedup used by KnowledgeSearchTool.
 *
 * Step tracking (STEPS, COUNTER) has been removed — replaced by StepTraceHook.
 * Max-steps enforcement has been removed — handled by ReActAgent.maxIters().
 *
 * Thread safety: ThreadLocal is per-thread; works correctly for synchronous dispatch.
 * For Reactor-based async dispatch across threads, dedup may not transfer — acceptable
 * since dedup is a performance optimization, not a correctness requirement.
 */
public class StepCollector {

    /** Tracks rewritten queries already searched within this request, to prevent duplicate RAG calls. */
    static final ThreadLocal<Set<String>> RETRIEVED_QUERIES =
            ThreadLocal.withInitial(HashSet::new);

    private StepCollector() {}

    /** Call at the start of each request to reset dedup state. */
    public static void init(int maxSteps) {
        RETRIEVED_QUERIES.get().clear();
    }

    /** Returns true if the given query has already been searched this request. */
    public static boolean isQueryRetrieved(String query) {
        return RETRIEVED_QUERIES.get().contains(query);
    }

    /** Marks a query as already searched for this request. */
    public static void markQueryRetrieved(String query) {
        RETRIEVED_QUERIES.get().add(query);
    }

    /** Must be called in a finally block to prevent ThreadLocal memory leaks. */
    public static void clear() {
        RETRIEVED_QUERIES.remove();
    }
}
