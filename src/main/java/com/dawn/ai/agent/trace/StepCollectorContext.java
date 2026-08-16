package com.dawn.ai.agent.trace;

import com.dawn.ai.agent.planning.PlanStep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 *   <li>{@code bashFailureStreak} — {@link AtomicInteger}, tracks consecutive empty/failed Bash observations</li>
 *   <li>{@code bashCircuitOpen} — once opened, prevents further Bash execution in this request</li>
 *   <li>{@code consecutiveRecoverySignals} — drives structured re-planning after unproductive outcomes</li>
 *   <li>{@code retrievedQueries} — {@link ConcurrentHashMap}-backed set</li>
 *   <li>{@code maxSteps}  — final, immutable</li>
 *   <li>{@code stepListener} — volatile; written once during init, read-only afterwards</li>
 * </ul>
 */
public final class StepCollectorContext {

    final List<AgentStep> steps = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger counter = new AtomicInteger(0);
    final AtomicInteger bashFailureStreak = new AtomicInteger(0);
    final AtomicBoolean bashCircuitOpen = new AtomicBoolean(false);
    final AtomicInteger consecutiveRecoverySignals = new AtomicInteger(0);
    final AtomicReference<String> pendingRecoveryGuidance = new AtomicReference<>();
    final int maxSteps;
    final Set<String> retrievedQueries = ConcurrentHashMap.newKeySet();
    volatile Consumer<AgentStep> stepListener;
    volatile List<PlanStep> currentPlan;
    volatile String userMessage;
    volatile Set<String> toolDescriptions;
    volatile boolean rePlanTriggered = false;

    StepCollectorContext(int maxSteps, Consumer<AgentStep> stepListener) {
        this.maxSteps = maxSteps;
        this.stepListener = stepListener;
    }

    // ── Re-plan context accessors ──

    public List<PlanStep> getCurrentPlan() { return currentPlan; }
    public void setCurrentPlan(List<PlanStep> plan) { this.currentPlan = plan; }

    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }

    public Set<String> getToolDescriptions() { return toolDescriptions; }
    public void setToolDescriptions(Set<String> toolDescriptions) { this.toolDescriptions = toolDescriptions; }

    public boolean isRePlanTriggered() { return rePlanTriggered; }
    public void markRePlanTriggered() { this.rePlanTriggered = true; }

    public int incrementConsecutiveRecoverySignals() {
        return consecutiveRecoverySignals.incrementAndGet();
    }

    public void resetConsecutiveRecoverySignals() {
        consecutiveRecoverySignals.set(0);
    }

    /**
     * 公开的步骤快照。供 sub-agent 执行器在 worker 线程结束后跨线程读取自己持有的
     * detached context 中累积的步骤（worker ThreadLocal 此时已清空）。
     */
    public List<AgentStep> snapshotSteps() {
        synchronized (steps) {
            return List.copyOf(steps);
        }
    }
}
