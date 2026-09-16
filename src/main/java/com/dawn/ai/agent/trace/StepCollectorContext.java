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
 * 把 {@link StepCollector} 单次请求的全部状态收拢成一个可变对象。
 *
 * <p>设计思路：ThreadLocal 传播（借助 Micrometer 的 {@code ThreadLocalAccessor}）复制的是
 * 这个对象的<em>引用</em>，不是深拷贝，所以所有拿到同一引用的 Reactor worker 线程
 * 操作的都是同一份底层状态。跨线程步数统计能正确，靠的就是这一点。
 *
 * <p>线程安全：
 * <ul>
 *   <li>{@code steps}     —— {@link Collections#synchronizedList}，保证并发追加安全</li>
 *   <li>{@code counter}   —— {@link AtomicInteger}，天然线程安全</li>
 *   <li>{@code bashFailureStreak} —— {@link AtomicInteger}，跟踪 Bash 连续为空/失败的观察次数</li>
 *   <li>{@code bashCircuitOpen} —— 一旦打开，本次请求内不再允许执行 Bash</li>
 *   <li>{@code consecutiveRecoverySignals} —— 连续无效结果后驱动结构化 re-plan</li>
 *   <li>{@code retrievedQueries} —— 基于 {@link ConcurrentHashMap} 的 set</li>
 *   <li>{@code maxSteps}  —— final，不可变</li>
 *   <li>{@code stepListener} —— volatile；init 时写一次，之后只读</li>
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

    // ── Re-plan 上下文访问器 ──

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
