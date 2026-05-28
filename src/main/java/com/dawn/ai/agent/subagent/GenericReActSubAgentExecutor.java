package com.dawn.ai.agent.subagent;

import com.dawn.ai.agent.trace.AgentStep;
import com.dawn.ai.agent.trace.StepCollector;
import com.dawn.ai.agent.trace.StepCollectorContext;
import com.dawn.ai.exception.AiConfigurationException;
import com.dawn.ai.exception.MaxStepsExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
    private final ExecutorService subAgentExecutor;

    public GenericReActSubAgentExecutor(ChatClient chatClient,
                                         SubAgentRegistry registry,
                                         @Qualifier("subAgentExecutor") ExecutorService subAgentExecutor) {
        this.chatClient = chatClient;
        this.registry = registry;
        this.subAgentExecutor = subAgentExecutor;
    }

    @Override
    public SubAgentResult execute(String type, String taskDescription, String parentSessionId,
                                   Consumer<AgentStep> progressListener) {
        long start = System.currentTimeMillis();

        Optional<SubAgentDefinition> defOpt = registry.get(type);
        if (defOpt.isEmpty()) {
            return SubAgentResult.failed("未知 sub-agent type: " + type, List.of(),
                    System.currentTimeMillis() - start);
        }
        SubAgentDefinition def = defOpt.get();

        StepCollectorContext subCtx = StepCollector.newDetachedContext(def.maxSteps(), progressListener);

        CompletableFuture<String> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> runReActOnWorker(def, taskDescription, subCtx),
                    subAgentExecutor);
        } catch (RejectedExecutionException ree) {
            log.warn("[SubAgent:{}] sub-agent pool saturated, parentSession={}", type, parentSessionId);
            return SubAgentResult.failed("sub-agent 线程池已满，请稍后再试", List.of(),
                    System.currentTimeMillis() - start);
        }

        try {
            String summary = future.get(def.timeoutSeconds(), TimeUnit.SECONDS);
            List<AgentStep> subSteps = subCtx.snapshotSteps();
            long duration = System.currentTimeMillis() - start;
            log.info("[SubAgent:{}] parentSession={}, status=SUCCESS, steps={}, durationMs={}",
                    type, parentSessionId, subSteps.size(), duration);
            return SubAgentResult.success(summary, subSteps, duration);

        } catch (TimeoutException te) {
            // 放弃 future 但不强行中断（Spring AI HTTP 调用不一定响应 interrupt）
            future.cancel(false);
            String reason = "sub-agent 执行超时 (" + def.timeoutSeconds() + "s)";
            List<AgentStep> partial = subCtx.snapshotSteps();
            long duration = System.currentTimeMillis() - start;
            log.warn("[SubAgent:{}] parentSession={}, status=PARTIAL_SUCCESS, reason=timeout, steps={}, durationMs={}",
                    type, parentSessionId, partial.size(), duration);
            return SubAgentResult.partial(composePartialSummary(partial, reason), partial, reason, duration);

        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            if (cause instanceof AiConfigurationException ace) {
                throw ace;
            }
            return handleExecutionFailure(type, def, parentSessionId, cause, subCtx, start);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            String reason = "sub-agent 被中断";
            List<AgentStep> partial = subCtx.snapshotSteps();
            long duration = System.currentTimeMillis() - start;
            log.warn("[SubAgent:{}] parentSession={}, status=FAILED, reason=interrupted, steps={}, durationMs={}",
                    type, parentSessionId, partial.size(), duration);
            return partial.isEmpty()
                    ? SubAgentResult.failed(reason, List.of(), duration)
                    : SubAgentResult.partial(composePartialSummary(partial, reason), partial, reason, duration);
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
                    .system(def.systemPrompt())
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
