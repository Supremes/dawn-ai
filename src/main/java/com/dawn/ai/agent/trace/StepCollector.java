package com.dawn.ai.agent.trace;

import com.dawn.ai.agent.planning.PlanStep;
import com.dawn.ai.exception.MaxStepsExceededException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 请求级步骤收集器，底层只有一个 {@link ThreadLocal}&lt;{@link StepCollectorContext}&gt;。
 *
 * <h3>跨线程正确性（流式模式）</h3>
 * 流式模式下，Spring AI 在 Reactor Netty worker 线程上执行工具回调，
 * 与调用 {@link #init} 的 {@code chatStreamExecutor} 线程并不是同一个。
 * 裸 {@code ThreadLocal} 的值跨线程不可见，之前因此出现过 {@link NullPointerException}
 * 以及工具静默失败。
 *
 * <p>修复方式是 <b>Micrometer context propagation</b>：
 * <ol>
 *   <li>把 {@link StepCollectorContextAccessor} 注册到 {@code ContextRegistry}。</li>
 *   <li>{@code Hooks.enableAutomaticContextPropagation()}（在 {@code AgentConfig} 中调用）
 *       让 Reactor 在订阅时把所有已注册的 ThreadLocal 捕获进响应式管道的 context。</li>
 *   <li>每个 operator 在任意线程上执行前，Reactor 都会通过 accessor 恢复 ThreadLocal，
 *       于是每个 worker 线程拿到的都是同一个 {@link StepCollectorContext} <em>引用</em>。</li>
 *   <li>所有线程共享同一个对象引用（不是副本），所以追加步骤、递增计数、标记查询
 *       这些修改对所有参与者立即可见。</li>
 * </ol>
 *
 * <h3>单次请求的生命周期</h3>
 * {@code AgentOrchestrator} 调用 {@link #init} → AOP/工具记录步骤 → {@link #collect} → {@link #clear}
 */
@Slf4j
public class StepCollector {

    private static final ThreadLocal<StepCollectorContext> CONTEXT = new ThreadLocal<>();

    // -------------------------------------------------------------------------
    // 生命周期
    // -------------------------------------------------------------------------

    /** 为当前请求初始化一个全新的 {@link StepCollectorContext}。 */
    public static void init(Integer maxSteps) {
        init(maxSteps, null);
    }

    /**
     * 流式重载：除了初始化请求上下文，还会注册一个实时的 {@code stepListener}。
     *
     * @param listener 可选；非流式请求传 {@code null}
     */
    public static void init(Integer maxSteps, Consumer<AgentStep> listener) {
        CONTEXT.set(new StepCollectorContext(maxSteps, listener));
    }

    // -------------------------------------------------------------------------
    // 包内访问器，供 StepCollectorContextAccessor 使用
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
    // Sub-agent 上下文管理（public，供 SubAgent 执行器使用）
    //
    // Sub-agent 在自己的 StepCollectorContext 中跑 ReAct，主 Agent 的步骤计数与
    // 步骤列表完全不被污染。{@link #snapshotContext()} 与 {@link #adoptContext}
    // 也用于把主 Agent ThreadLocal 显式搬到另一线程（CompletableFuture worker）。
    // -------------------------------------------------------------------------

    /** 创建一个全新的、未挂载到 ThreadLocal 的 sub-agent 上下文。 */
    public static StepCollectorContext newDetachedContext(int maxSteps, Consumer<AgentStep> listener) {
        return new StepCollectorContext(maxSteps, listener);
    }

    /** 当前线程 ThreadLocal 持有的上下文（可能为 null）。 */
    public static StepCollectorContext snapshotContext() {
        return CONTEXT.get();
    }

    /** 将指定上下文挂到当前线程；{@code null} 表示清除。 */
    public static void adoptContext(StepCollectorContext ctx) {
        if (ctx == null) {
            CONTEXT.remove();
        } else {
            CONTEXT.set(ctx);
        }
    }

    // -------------------------------------------------------------------------
    // 公开 API
    // -------------------------------------------------------------------------

    /** 由 {@link ToolExecutionAspect} 在每次工具调用完成或失败后调用。 */
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
     * 返回下一个单调递增的步骤编号，并强制执行单次请求的步数上限。
     * 由 {@link ToolExecutionAspect} 在每次工具调用前调用。
     */
    public static int getAndIncreaseStepNumber() {
        StepCollectorContext ctx = CONTEXT.get();
        if (ctx == null) {
            // 上下文尚未传播过来。开启了 Micrometer 自动传播后按理不该发生，
            // 这里打清楚日志并放行调用，而不是直接 crash。
            log.warn("[StepCollector] getAndIncreaseStepNumber() called on thread '{}' with no active context. " +
                     "Verify that Hooks.enableAutomaticContextPropagation() is enabled.",
                    Thread.currentThread().getName());
            return 1;
        }
        int next = ctx.counter.incrementAndGet();
        if (next > ctx.maxSteps) {
            log.error("[StepCollector] Exceeded max steps: {} > {}", next, ctx.maxSteps);
            throw new MaxStepsExceededException("已达到工具调用上限（" + ctx.maxSteps +
                    " 次）。不要再调用任何工具；请基于已有工具观察总结回答，" +
                    "如果仍无法确认事实，请明确说明无法确认。");
        }
        return next;
    }

    /** 返回当前请求已记录的全部步骤的快照。 */
    public static List<AgentStep> collect() {
        StepCollectorContext ctx = CONTEXT.get();
        if (ctx == null) return List.of();
        return new ArrayList<>(ctx.steps);
    }

    /**
     * 给定的改写后查询在本次请求中是否已经检索过，是则返回 {@code true}。
     * 供 {@code KnowledgeSearchTool} 用来避免重复的 RAG 调用。
     */
    public static boolean isQueryRetrieved(String query) {
        StepCollectorContext ctx = CONTEXT.get();
        return ctx != null && ctx.retrievedQueries.contains(query);
    }

    /**
     * 把一条改写后的查询标记为本次请求已检索。
     * 请在执行 RAG 检索之前立即调用。
     */
    public static void markQueryRetrieved(String query) {
        StepCollectorContext ctx = CONTEXT.get();
        if (ctx != null) {
            ctx.retrievedQueries.add(query);
        }
    }

    /**
     * 跟踪 BashTool 连续失败或为空的观察结果。
     * 当前观察结果达到停止阈值时返回 {@code true}。
     */
    public static boolean recordBashObservation(boolean failedOrEmpty, int stopThreshold) {
        StepCollectorContext ctx = CONTEXT.get();
        if (ctx == null || stopThreshold <= 0) {
            return false;
        }
        if (ctx.bashCircuitOpen.get()) {
            return true;
        }
        if (!failedOrEmpty) {
            ctx.bashFailureStreak.set(0);
            return false;
        }
        if (ctx.bashFailureStreak.incrementAndGet() < stopThreshold) {
            return false;
        }
        ctx.bashCircuitOpen.set(true);
        return true;
    }

    public static boolean isBashCircuitOpen() {
        StepCollectorContext ctx = CONTEXT.get();
        return ctx != null && ctx.bashCircuitOpen.get();
    }

    // -------------------------------------------------------------------------
    // Re-plan 上下文管理
    // -------------------------------------------------------------------------

    /** 为当前请求设置 re-plan 上下文（计划、用户消息、工具描述）。 */
    public static void setRePlanContext(List<PlanStep> plan, String userMessage, Set<String> toolDescriptions) {
        StepCollectorContext ctx = CONTEXT.get();
        if (ctx != null) {
            ctx.currentPlan = plan;
            ctx.userMessage = userMessage;
            ctx.toolDescriptions = toolDescriptions;
        }
    }

    public static List<PlanStep> getCurrentPlan() {
        StepCollectorContext ctx = CONTEXT.get();
        return ctx != null ? ctx.currentPlan : null;
    }

    public static String getUserMessage() {
        StepCollectorContext ctx = CONTEXT.get();
        return ctx != null ? ctx.userMessage : null;
    }

    public static Set<String> getToolDescriptions() {
        StepCollectorContext ctx = CONTEXT.get();
        return ctx != null ? ctx.toolDescriptions : null;
    }

    public static int incrementConsecutiveRecoverySignals() {
        StepCollectorContext ctx = CONTEXT.get();
        return ctx != null ? ctx.consecutiveRecoverySignals.incrementAndGet() : 0;
    }

    public static void resetConsecutiveRecoverySignals() {
        StepCollectorContext ctx = CONTEXT.get();
        if (ctx != null) {
            ctx.consecutiveRecoverySignals.set(0);
        }
    }

    public static boolean isRePlanTriggered() {
        StepCollectorContext ctx = CONTEXT.get();
        return ctx != null && ctx.rePlanTriggered;
    }

    public static void markRePlanTriggered() {
        StepCollectorContext ctx = CONTEXT.get();
        if (ctx != null) {
            ctx.rePlanTriggered = true;
        }
    }

    public static void setPendingRecoveryGuidance(String guidance) {
        StepCollectorContext ctx = CONTEXT.get();
        if (ctx != null && guidance != null && !guidance.isBlank()) {
            ctx.pendingRecoveryGuidance.set(guidance);
        }
    }

    public static String consumePendingRecoveryGuidance() {
        StepCollectorContext ctx = CONTEXT.get();
        return ctx != null ? ctx.pendingRecoveryGuidance.getAndSet(null) : null;
    }

    /** 必须在 {@code finally} 块中调用，防止 ThreadLocal 内存泄漏。 */
    public static void clear() {
        CONTEXT.remove();
    }
}
