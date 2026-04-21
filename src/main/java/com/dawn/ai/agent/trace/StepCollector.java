package com.dawn.ai.agent.trace;

import com.dawn.ai.exception.MaxStepsExceededException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Request-scoped step collector backed by a single {@link ThreadLocal}&lt;{@link StepCollectorContext}&gt;.
 *
 * <h3>Cross-thread correctness (streaming mode)</h3>
 * In streaming mode, Spring AI executes tool callbacks on Reactor Netty worker threads
 * — different from the {@code chatStreamExecutor} thread that calls {@link #init}.
 * Raw {@code ThreadLocal} values are invisible across thread boundaries, which previously
 * caused a {@link NullPointerException} and silent tool failures.
 *
 * <p>The fix uses <b>Micrometer context propagation</b>:
 * <ol>
 *   <li>{@link StepCollectorContextAccessor} is registered with {@code ContextRegistry}.</li>
 *   <li>{@code Hooks.enableAutomaticContextPropagation()} (called in {@code AgentConfig})
 *       tells Reactor to capture all registered ThreadLocals into the reactive pipeline's
 *       context at subscription time.</li>
 *   <li>Before each operator executes on any thread, Reactor restores the ThreadLocals
 *       via the accessor — giving every worker thread the same {@link StepCollectorContext}
 *       <em>reference</em>.</li>
 *   <li>Because all threads share the same object reference (not a copy), mutations
 *       (appending steps, incrementing counter, marking queries) are immediately visible
 *       to all participants.</li>
 * </ol>
 *
 * <h3>Lifecycle per request</h3>
 * {@code AgentOrchestrator} calls {@link #init} → AOP/tools record steps → {@link #collect} → {@link #clear}
 */
@Slf4j
public class StepCollector {

    private static final ThreadLocal<StepCollectorContext> CONTEXT = new ThreadLocal<>();

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Initialises a fresh {@link StepCollectorContext} for the current request. */
    public static void init(Integer maxSteps) {
        init(maxSteps, null);
    }

    /**
     * Streaming overload: registers a real-time {@code stepListener} in addition to
     * initialising the request context.
     *
     * @param listener optional; {@code null} for non-streaming requests
     */
    public static void init(Integer maxSteps, Consumer<AgentStep> listener) {
        CONTEXT.set(new StepCollectorContext(maxSteps, listener));
    }

    // -------------------------------------------------------------------------
    // Package-private accessors used by StepCollectorContextAccessor
    // -------------------------------------------------------------------------

    static StepCollectorContext currentContext() {
        return CONTEXT.get();
    }

    static void setContext(StepCollectorContext ctx) {
        CONTEXT.set(ctx);
    }

    static void clearContext() {
        CONTEXT.remove();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Called by {@link ToolExecutionAspect} after each successful tool invocation. */
    public static void record(AgentStep step) {
        StepCollectorContext ctx = CONTEXT.get();
        if (ctx == null) {
            log.warn("[StepCollector] record() called on thread '{}' with no active context — step dropped.",
                    Thread.currentThread().getName());
            return;
        }
        ctx.steps.add(step);
        Consumer<AgentStep> listener = ctx.stepListener;
        if (listener != null) {
            listener.accept(step);
        }
    }

    /**
     * Returns the next monotonically increasing step number and enforces the per-request
     * step limit.  Called by {@link ToolExecutionAspect} before every tool invocation.
     */
    public static int getAndIncreaseStepNumber() {
        StepCollectorContext ctx = CONTEXT.get();
        if (ctx == null) {
            // Context not yet propagated — should not happen with Micrometer auto-propagation,
            // but log clearly and allow the call to proceed rather than crash.
            log.warn("[StepCollector] getAndIncreaseStepNumber() called on thread '{}' with no active context. " +
                     "Verify that Hooks.enableAutomaticContextPropagation() is enabled.",
                    Thread.currentThread().getName());
            return 1;
        }
        int next = ctx.counter.incrementAndGet();
        if (next > ctx.maxSteps) {
            log.error("[StepCollector] Exceeded max steps: {} > {}", next, ctx.maxSteps);
            throw new MaxStepsExceededException("Exceeded Max Steps: " + ctx.maxSteps);
        }
        return next;
    }

    /** Returns a snapshot of all steps recorded for the current request. */
    public static List<AgentStep> collect() {
        StepCollectorContext ctx = CONTEXT.get();
        if (ctx == null) return List.of();
        return new ArrayList<>(ctx.steps);
    }

    /**
     * Returns {@code true} if the given rewritten query has already been searched this
     * request.  Used by {@code KnowledgeSearchTool} to prevent duplicate RAG calls.
     */
    public static boolean isQueryRetrieved(String query) {
        StepCollectorContext ctx = CONTEXT.get();
        return ctx != null && ctx.retrievedQueries.contains(query);
    }

    /**
     * Marks a rewritten query as already searched for this request.
     * Call immediately before executing a RAG retrieval.
     */
    public static void markQueryRetrieved(String query) {
        StepCollectorContext ctx = CONTEXT.get();
        if (ctx != null) {
            ctx.retrievedQueries.add(query);
        }
    }

    /** Must be called in a {@code finally} block to prevent ThreadLocal memory leaks. */
    public static void clear() {
        CONTEXT.remove();
    }
}
