package com.dawn.ai.agent.subagent;

import com.dawn.ai.agent.skill.Skill;
import com.dawn.ai.agent.skill.SkillRegistry;
import com.dawn.ai.agent.trace.AgentStep;
import com.dawn.ai.agent.trace.StepCollector;
import com.dawn.ai.agent.trace.StepCollectorContext;
import com.dawn.ai.config.AiInteractionContext;
import com.dawn.ai.exception.AiConfigurationException;
import com.dawn.ai.exception.MaxStepsExceededException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 通用 ReAct sub-agent 执行器：所有 {@link SubAgentDefinition} 共用同一执行内核，
 * 差异只在 prompt / 工具白名单 / 资源边界。
 *
 * <h3>关键不变式</h3>
 * <ul>
 *   <li>sub-agent 跑在专用线程池（{@code subAgentExecutor}），独立于主 SSE 流。</li>
 *   <li>sub-agent 拥有<b>独立</b>的 {@link StepCollectorContext}，与主 Agent 完全隔离：
 *       主 Agent 的 step 计数 / 步骤列表 / 去重缓存均不被污染。</li>
 *   <li>超时由 {@link CompletableFuture#get(long, TimeUnit)} 强制；超时后<b>放弃</b>
 *       future（不强行中断 HTTP 调用），从 sub context 收割已完成的步骤拼出 partial summary。</li>
 *   <li>{@link AiConfigurationException} 不可恢复，向外抛；其余异常一律落到 PARTIAL/FAILED。</li>
 * </ul>
 */
@Slf4j
@Service
public class GenericReActSubAgentExecutor implements SubAgentExecutor {

    private final ChatClient chatClient;
    private final SubAgentRegistry registry;
    private final SkillRegistry skillRegistry;
    private final ExecutorService subAgentExecutor;
    private final MeterRegistry meterRegistry;

    public GenericReActSubAgentExecutor(ChatClient chatClient,
                                         SubAgentRegistry registry,
                                         SkillRegistry skillRegistry,
                                         @Qualifier("subAgentExecutor") ExecutorService subAgentExecutor,
                                         MeterRegistry meterRegistry) {
        this.chatClient = chatClient;
        this.registry = registry;
        this.skillRegistry = skillRegistry;
        this.subAgentExecutor = subAgentExecutor;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Metrics 命名与现有体系对齐（{@code ai.tool.*} / {@code ai.rag.*} / {@code ai.planner.*}）:
     * <ul>
     *   <li>{@code ai.subagent.dispatches{type, status}} — 累计派发次数，按结果分桶</li>
     *   <li>{@code ai.subagent.duration{type, status}} — 派发耗时分布</li>
     *   <li>{@code ai.subagent.steps{type}} — 单次派发的内部步数分布（含失败时已完成步数）</li>
     * </ul>
     */
    private void recordMetrics(String type, SubAgentExecutionStatus status, long durationMs, int subStepCount) {
        Counter.builder("ai.subagent.dispatches")
                .description("Sub-agent dispatch count by type and outcome status")
                .tag("type", type)
                .tag("status", status.name())
                .register(meterRegistry)
                .increment();
        Timer.builder("ai.subagent.duration")
                .description("Sub-agent end-to-end execution duration")
                .tag("type", type)
                .tag("status", status.name())
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
        DistributionSummary.builder("ai.subagent.steps")
                .description("Number of internal ReAct steps per sub-agent dispatch")
                .tag("type", type)
                .register(meterRegistry)
                .record(subStepCount);
    }

    @Override
    public SubAgentResult execute(String type, String taskDescription, String parentSessionId,
                                   Consumer<AgentStep> progressListener) {
        long start = System.currentTimeMillis();

        Optional<SubAgentDefinition> defOpt = registry.get(type);
        if (defOpt.isEmpty()) {
            SubAgentResult failed = SubAgentResult.failed("未知 sub-agent type: " + type, List.of(),
                    System.currentTimeMillis() - start);
            recordMetrics(type, failed.status(), failed.durationMs(), 0);
            return failed;
        }
        SubAgentDefinition def = defOpt.get();

        StepCollectorContext subCtx = StepCollector.newDetachedContext(def.maxSteps(), progressListener);

        // 把主线程的 AiInteractionContext（含 sessionId）传播到 worker，
        // 使 sub-agent 的 LLM 调用在 Langfuse Session 视图中归到同一会话。
        java.util.concurrent.Callable<String> task = AiInteractionContext.wrap(
                () -> runReActOnWorker(def, taskDescription, subCtx));

        CompletableFuture<String> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, subAgentExecutor);
        } catch (RejectedExecutionException ree) {
            log.warn("[SubAgent:{}] sub-agent pool saturated, parentSession={}", type, parentSessionId);
            SubAgentResult failed = SubAgentResult.failed("sub-agent 线程池已满，请稍后再试", List.of(),
                    System.currentTimeMillis() - start);
            recordMetrics(type, failed.status(), failed.durationMs(), 0);
            return failed;
        }

        try {
            String summary = future.get(def.timeoutSeconds(), TimeUnit.SECONDS);
            List<AgentStep> subSteps = subCtx.snapshotSteps();
            long duration = System.currentTimeMillis() - start;
            log.info("[SubAgent:{}] parentSession={}, status=SUCCESS, steps={}, durationMs={}",
                    type, parentSessionId, subSteps.size(), duration);
            SubAgentResult result = SubAgentResult.success(summary, subSteps, duration);
            recordMetrics(type, result.status(), duration, subSteps.size());
            return result;

        } catch (TimeoutException te) {
            // 放弃 future 但不强行中断（Spring AI HTTP 调用不一定响应 interrupt）
            future.cancel(false);
            String reason = "sub-agent 执行超时 (" + def.timeoutSeconds() + "s)";
            List<AgentStep> partial = subCtx.snapshotSteps();
            long duration = System.currentTimeMillis() - start;
            log.warn("[SubAgent:{}] parentSession={}, status=PARTIAL_SUCCESS, reason=timeout, steps={}, durationMs={}",
                    type, parentSessionId, partial.size(), duration);
            SubAgentResult result = SubAgentResult.partial(composePartialSummary(partial, reason),
                    partial, reason, duration);
            recordMetrics(type, result.status(), duration, partial.size());
            return result;

        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            if (cause instanceof AiConfigurationException ace) {
                throw ace;
            }
            SubAgentResult result = handleExecutionFailure(type, def, parentSessionId, cause, subCtx, start);
            recordMetrics(type, result.status(), result.durationMs(), result.subSteps().size());
            return result;

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            String reason = "sub-agent 被中断";
            List<AgentStep> partial = subCtx.snapshotSteps();
            long duration = System.currentTimeMillis() - start;
            log.warn("[SubAgent:{}] parentSession={}, status=FAILED, reason=interrupted, steps={}, durationMs={}",
                    type, parentSessionId, partial.size(), duration);
            SubAgentResult result = partial.isEmpty()
                    ? SubAgentResult.failed(reason, List.of(), duration)
                    : SubAgentResult.partial(composePartialSummary(partial, reason), partial, reason, duration);
            recordMetrics(type, result.status(), duration, partial.size());
            return result;
        }
    }

    private SubAgentResult handleExecutionFailure(String type, SubAgentDefinition def, String parentSessionId,
                                                   Throwable cause, StepCollectorContext subCtx, long start) {
        List<AgentStep> partial = subCtx.snapshotSteps();
        long duration = System.currentTimeMillis() - start;

        if (cause instanceof MaxStepsExceededException) {
            String reason = "sub-agent 达到步数上限 (" + def.maxSteps() + ")";
            log.warn("[SubAgent:{}] parentSession={}, status=PARTIAL_SUCCESS, reason=max-steps, steps={}, durationMs={}",
                    type, parentSessionId, partial.size(), duration);
            return SubAgentResult.partial(composePartialSummary(partial, reason), partial, reason, duration);
        }

        String reason = cause.getClass().getSimpleName()
                + (cause.getMessage() != null ? (": " + cause.getMessage()) : "");
        log.warn("[SubAgent:{}] parentSession={}, status={}, reason={}, steps={}, durationMs={}",
                type, parentSessionId, partial.isEmpty() ? "FAILED" : "PARTIAL_SUCCESS",
                reason, partial.size(), duration);
        return partial.isEmpty()
                ? SubAgentResult.failed(reason, List.of(), duration)
                : SubAgentResult.partial(composePartialSummary(partial, reason), partial, reason, duration);
    }

    private String runReActOnWorker(SubAgentDefinition def, String task, StepCollectorContext subCtx) {
        StepCollectorContext previous = StepCollector.snapshotContext();
        StepCollector.adoptContext(subCtx);
        try {
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .system(def.systemPrompt() + formatSkills())
                    .user(task)
                    .toolNames(def.allowedTools().toArray(String[]::new));

            if (def.modelOverride() != null || def.temperatureOverride() != null) {
                OpenAiChatOptions.Builder ob = OpenAiChatOptions.builder();
                if (def.modelOverride() != null) {
                    ob.model(def.modelOverride());
                }
                if (def.temperatureOverride() != null) {
                    ob.temperature(def.temperatureOverride());
                }
                spec = spec.options(ob.build());
            }

            return spec.call().chatResponse().getResult().getOutput().getText();
        } finally {
            StepCollector.adoptContext(previous);
        }
    }

    private String formatSkills() {
        Collection<Skill> all = skillRegistry.list();
        if (all.isEmpty()) {
            return "\n\n## 可用 Skills\n当前没有可用 Skills；不要调用 loadSkillTool 或 readSkillResourceTool。";
        }
        StringBuilder sb = new StringBuilder("\n\n## 可用 Skills\n")
                .append("仅当下方某个 skill 的 name 和 description 明确匹配当前任务时，")
                .append("才调用 `loadSkillTool(name)` 加载完整指令；")
                .append("需要 skill 的内嵌资源时调用 `readSkillResourceTool(skill, path)`。")
                .append("只能使用下方列出的 skill name，不要发明或猜测不存在的 skill。\n\n");
        for (Skill skill : all) {
            sb.append("- **").append(skill.manifest().name()).append("**: ")
                    .append(skill.manifest().description()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 把已完成步骤拼成给主 Agent 的 partial summary。
     * 不再发 LLM 调用——失败时再花 token 风险更高，且摘要的可靠性来自原始步骤记录。
     */
    private String composePartialSummary(List<AgentStep> steps, String reason) {
        if (steps.isEmpty()) {
            return "（子 Agent 未完成任何可用步骤即结束，原因：" + reason + "）";
        }
        StringBuilder sb = new StringBuilder("（子 Agent 部分完成，原因：").append(reason).append("）\n\n")
                .append("已执行步骤摘要：\n");
        List<AgentStep> copy = new ArrayList<>(steps);
        for (AgentStep s : copy) {
            sb.append("- 步骤").append(s.stepNumber()).append(" [").append(s.toolName()).append("] ");
            String out = s.toolOutput() == null ? "" : s.toolOutput();
            sb.append(out, 0, Math.min(200, out.length()));
            if (out.length() > 200) {
                sb.append("…");
            }
            sb.append("\n");
        }
        sb.append("\n基于以上已检索信息，请主 Agent 判断是否够用：够用直接总结；不够可换角度再派一次。");
        return sb.toString();
    }
}
