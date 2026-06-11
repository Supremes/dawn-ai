package com.dawn.ai.agent.tools;

import com.dawn.ai.agent.subagent.SubAgentExecutionStatus;
import com.dawn.ai.agent.subagent.SubAgentExecutor;
import com.dawn.ai.agent.subagent.SubAgentRegistry;
import com.dawn.ai.agent.subagent.SubAgentResult;
import com.dawn.ai.agent.trace.AgentStep;
import com.dawn.ai.agent.trace.StepCollector;
import com.dawn.ai.agent.trace.SubStepProvider;
import com.dawn.ai.config.AiInteractionContext;
import com.dawn.ai.sse.ChatStreamEvent;
import com.dawn.ai.sse.StreamSinkHolder;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 主 Agent 把"重活"派给隔离上下文 sub-agent 的入口工具。
 *
 * <h3>语义</h3>
 * <ul>
 *   <li>sub-agent 在<b>独立</b>的 {@link com.dawn.ai.agent.trace.StepCollectorContext} 中跑 ReAct，
 *       主 Agent 上下文不被污染。</li>
 *   <li>sub-agent 看不到主对话历史；任务上下文必须由主 Agent 在 {@code taskDescription} 里自包含给出。</li>
 *   <li>sub-agent 不写主 Memory。</li>
 *   <li>单次主请求最多派 {@code app.ai.subagent.max-dispatches-per-session}（默认 3）次。
 *       超过上限直接返回拒绝（不抛异常，让 LLM 看见边界）。</li>
 * </ul>
 *
 * <p>本工具自身被 {@link com.dawn.ai.agent.trace.ToolExecutionAspect} 记一步到主 collector
 * （toolName=DispatchSubAgentTool），output 中的 {@code subSteps} 字段仅用于观测，
 * 不会让主 Agent 的 maxSteps 计数误算 sub-agent 内部步骤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Description("把需要长时间检索 / 多轮 RAG / 长文档分析的'重活'派给隔离上下文的子 Agent，避免污染主对话上下文。仅在单次 knowledge_search 1-2 次不够用的深度调研场景使用；简单问题直接用其他工具，不要派。Input: subagentType (固定为 'research'), taskDescription (自包含的任务描述，子 Agent 看不到对话历史)。单次对话最多派 3 次。")
public class DispatchSubAgentTool implements Function<DispatchSubAgentTool.Request, DispatchSubAgentTool.Response> {

    private static final String TOOL_NAME = DispatchSubAgentTool.class.getSimpleName();

    private final SubAgentExecutor subAgentExecutor;
    private final SubAgentRegistry registry;

    @Value("${app.ai.subagent.max-dispatches-per-session:3}")
    private int maxDispatchesPerSession;

    /**
     * 检索型子 Agent 止损阈值：本会话内知识库空检索（docsFound=0）累计达到此值后，
     * 拒绝再派发以 {@code knowledgeSearchTool} 为主力的子 Agent。
     */
    @Value("${app.ai.subagent.block-after-empty-searches:2}")
    private int blockAfterEmptySearches;

    public record Request(
            @JsonProperty(required = true)
            @JsonPropertyDescription("子 Agent 类型。当前仅支持 'research'（深度知识库检索 + 综合）。")
            String subagentType,

            @JsonProperty(required = true)
            @JsonPropertyDescription("给子 Agent 的任务描述。必须自包含：包含背景、目标、希望产出的形态。子 Agent 看不到当前对话历史，所有必要上下文都要在这里写清。")
            String taskDescription
    ) {}

    public record Response(
            String status,
            String summary,
            int subStepCount,
            long durationMs,
            String failureReason,
            List<AgentStep> subSteps
    ) implements SubStepProvider {

        @Override
        public List<AgentStep> getSubSteps() {
            return subSteps == null ? List.of() : subSteps;
        }

        static Response from(SubAgentResult result) {
            return new Response(
                    result.status().name(),
                    result.summary(),
                    result.subSteps().size(),
                    result.durationMs(),
                    result.failureReason(),
                    result.subSteps());
        }

        static Response refused(String reason) {
            return new Response("REFUSED", reason, 0, 0, reason, List.of());
        }
    }

    @Override
    public Response apply(Request request) {
        if (request == null || request.subagentType() == null || request.subagentType().isBlank()) {
            return Response.refused("缺少必填参数 subagentType");
        }
        if (request.taskDescription() == null || request.taskDescription().isBlank()) {
            return Response.refused("缺少必填参数 taskDescription");
        }
        var definition = registry.get(request.subagentType());
        if (definition.isEmpty()) {
            return Response.refused("未知 sub-agent type: " + request.subagentType()
                    + "（当前可用: " + registry.list().stream().map(d -> d.type()).toList() + "）");
        }

        // P1 止损：检索型子 Agent（白名单含 knowledgeSearchTool）在本会话已多次空检索时拒绝派发。
        // 库中确无相关内容时，再派一轮以检索为主力的 ReAct 也是空转，应让主 Agent 回退到自身知识。
        // 注：依赖 KnowledgeSearchTool.Response 的 toString 含 "docsFound=0"，二者耦合需同步维护。
        if (definition.get().allowedTools().contains("knowledgeSearchTool")) {
            long emptySearches = StepCollector.collect().stream()
                    .filter(s -> KnowledgeSearchTool.class.getSimpleName().equals(s.toolName()))
                    .filter(s -> s.toolOutput() != null && s.toolOutput().contains("docsFound=0"))
                    .count();
            if (emptySearches >= blockAfterEmptySearches) {
                log.info("[DispatchSubAgentTool] 拒绝派发 type={}：本会话已有 {} 次知识库空检索，库中应无相关内容",
                        request.subagentType(), emptySearches);
                return Response.refused("知识库已检索 " + emptySearches
                        + " 次均无结果，说明库中没有相关内容。请直接基于自身知识作答，不要派发检索型子 Agent。");
            }
        }

        long alreadyDispatched = StepCollector.collect().stream()
                .filter(s -> TOOL_NAME.equals(s.toolName()))
                .count();
        if (alreadyDispatched >= maxDispatchesPerSession) {
            log.warn("[DispatchSubAgentTool] 派发上限已达 {}，本次请求拒绝。type={}",
                    maxDispatchesPerSession, request.subagentType());
            return Response.refused("已达到 sub-agent 派发上限 ("
                    + maxDispatchesPerSession + " 次/对话)，请基于已收集的信息直接生成回答。");
        }

        String parentSessionId = resolveParentSessionId();
        log.info("[DispatchSubAgentTool] dispatching: type={}, parentSession={}, dispatchedSoFar={}, taskChars={}",
                request.subagentType(), parentSessionId, alreadyDispatched, request.taskDescription().length());

        Consumer<AgentStep> progressListener = buildProgressListener(request.subagentType(), parentSessionId);

        SubAgentResult result = subAgentExecutor.execute(
                request.subagentType(),
                request.taskDescription(),
                parentSessionId,
                progressListener);

        if (result.status() == SubAgentExecutionStatus.FAILED) {
            log.warn("[DispatchSubAgentTool] sub-agent FAILED: type={}, reason={}",
                    request.subagentType(), result.failureReason());
        }
        return Response.from(result);
    }

    /**
     * 仅在流式请求中构造 sub-step 心跳监听器；非流式时 sink 为空，返回 {@code null}
     * 表示 sub-agent 静默执行（最终 AgentStep.subSteps 仍会通过 SubStepProvider 上报）。
     */
    private Consumer<AgentStep> buildProgressListener(String subAgentType, String sessionId) {
        Consumer<ChatStreamEvent> sink = StreamSinkHolder.get();
        if (sink == null) {
            return null;
        }
        return subStep -> sink.accept(ChatStreamEvent.subProgress(
                sessionId, TOOL_NAME, subAgentType, subStep.stepNumber(), subStep.toolName()));
    }

    private String resolveParentSessionId() {
        String fromContext = AiInteractionContext.getSessionId();
        return (fromContext != null && !fromContext.isBlank()) ? fromContext : "unknown";
    }
}
