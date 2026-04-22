package com.dawn.ai.agent.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Holds all per-request state for {@link StepCollector} as a single mutable object.
 *
 * <p>Design rationale: ThreadLocal propagation (via Micrometer's {@code ThreadLocalAccessor})
 * copies the <em>reference</em> to this object — not a deep copy — so all Reactor worker
 * threads that receive the same reference work on the same underlying state.  This is the
 * key property that makes cross-thread step counting correct.
 *
 * <p>Thread safety:
 * <ul>
 *   <li>{@code steps}     — {@link Collections#synchronizedList} for safe concurrent appends</li>
 *   <li>{@code counter}   — {@link AtomicInteger}, inherently thread-safe</li>
 *   <li>{@code retrievedQueries} — {@link ConcurrentHashMap}-backed set</li>
 *   <li>{@code maxSteps}  — final, immutable</li>
 *   <li>{@code stepListener} — volatile; written once during init, read-only afterwards</li>
 * </ul>
 */
public final class StepCollectorContext {

    final List<AgentStep> steps = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger counter = new AtomicInteger(0);
    final int maxSteps;
    final Set<String> retrievedQueries = ConcurrentHashMap.newKeySet();
    volatile Consumer<AgentStep> stepListener;

    StepCollectorContext(int maxSteps, Consumer<AgentStep> stepListener) {
        this.maxSteps = maxSteps;
        this.stepListener = stepListener;
    }
}
