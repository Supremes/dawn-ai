package com.dawn.ai.agent.orchestration;

import com.dawn.ai.agent.planning.PlanStep;
import com.dawn.ai.agent.planning.TaskPlanner;
import com.dawn.ai.agent.registry.ToolRegistry;
import com.dawn.ai.agent.skill.Skill;
import com.dawn.ai.agent.skill.SkillRegistry;
import com.dawn.ai.agent.subagent.SubAgentDefinition;
import com.dawn.ai.agent.subagent.SubAgentRegistry;
import com.dawn.ai.agent.trace.AgentStep;
import com.dawn.ai.agent.trace.StepCollector;
import com.dawn.ai.agent.tools.KnowledgeSearchTool;
import com.dawn.ai.exception.AiConfigurationException;
import com.dawn.ai.exception.LLMProviderException;
import com.dawn.ai.exception.MaxStepsExceededException;
import com.dawn.ai.exception.PlanGenerationException;
import com.dawn.ai.memory.MemoryManager;
import com.dawn.ai.memory.MemoryType;
import com.dawn.ai.memory.UserProfileService;
import com.dawn.ai.service.MemoryService;
import com.dawn.ai.sse.ChatStreamEvent;
import com.dawn.ai.sse.StreamSinkHolder;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.Map;

/**
 * Agent Orchestrator — orchestrates the full ReAct loop with planning and step tracing.
 *
 * Flow per streaming request:
 *  1. StepCollector.init()         — reset thread-local state
 *  2. TaskPlanner.plan()           — pre-execution planning via a separate LLM call (optional)
 *  3. Build system prompt          — base prompt + plan summary + max-steps instruction
 *  4. chatClient + .toolNames()    — Spring AI handles the tool-calling loop
 *  5. ToolExecutionAspect (AOP)    — intercepts each tool call, records it automatically
 *  6. StepCollector.collect()      — gather all recorded steps
 *  7. Publish SSE events           — stream plan, thinking, steps, tokens, done/error
 *  8. Persist to memory            — save turn to Redis
 *  9. StepCollector.clear()        — prevent ThreadLocal memory leak
 */
@Slf4j
@Service
public class AgentOrchestrator {

    private final ChatClient chatClient;
    private final MemoryService memoryService;
    private final MemoryManager memoryManager;
    private final TaskPlanner taskPlanner;
    private final ToolRegistry toolRegistry;
    private final MeterRegistry meterRegistry;
    private final UserProfileService userProfileService;
    private final SkillRegistry skillRegistry;
    private final SubAgentRegistry subAgentRegistry;

    public AgentOrchestrator(ChatClient chatClient,
                              MemoryService memoryService,
                              MemoryManager memoryManager,
                              TaskPlanner taskPlanner,
                              ToolRegistry toolRegistry,
                              MeterRegistry meterRegistry,
                              UserProfileService userProfileService,
                              SkillRegistry skillRegistry,
                              SubAgentRegistry subAgentRegistry) {
        this.chatClient = chatClient;
        this.memoryService = memoryService;
        this.memoryManager = memoryManager;
        this.taskPlanner = taskPlanner;
        this.toolRegistry = toolRegistry;
        this.meterRegistry = meterRegistry;
        this.userProfileService = userProfileService;
        this.skillRegistry = skillRegistry;
        this.subAgentRegistry = subAgentRegistry;
    }

    @Value("${app.ai.system-prompt:You are a helpful AI assistant.}")
    private String baseSystemPrompt;

    @Value("${app.ai.react.max-steps:10}")
    private int maxSteps;

    @Value("${app.ai.react.plan-enabled:true}")
    private boolean planEnabled;

    @Value("${spring.ai.openai.chat.options.model:qwen-plus}")
    private String model;

    @Value("${app.ai.subagent.max-dispatches-per-session:3}")
    private int maxSubAgentDispatches;

    @Value("${app.memory.default-user-id:local-user}")
    private String defaultUserId;

    @Value("${app.memory.injection.procedural-top-k:2}")
    private int proceduralTopK;

    @Value("${app.memory.injection.semantic-top-k:3}")
    private int semanticTopK;

    private DistributionSummary ragCallsSummary;

    @PostConstruct
    void initMetrics() {
        ragCallsSummary = DistributionSummary.builder("ai.rag.calls_per_session")
                .description("Number of knowledgeSearchTool calls per agent session")
                .register(meterRegistry);
    }

    private TaskPlanner.PlannerResult resolvePlan(String sessionId, String userMessage) {
        if (!planEnabled) {
            return TaskPlanner.PlannerResult.empty();
        }

        try {
            return taskPlanner.plan(userMessage, toolRegistry.getDescriptions(), buildPlannerContext(sessionId));
        } catch (PlanGenerationException exception) {
            log.warn("[AgentOrchestrator] Planner failed, falling back to direct execution. userMsg={}, reason={}",
                    userMessage.substring(0, Math.min(50, userMessage.length())),
                    exception.getMessage());
            return TaskPlanner.PlannerResult.empty();
        }
    }

    private static final int PLANNER_CONTEXT_TURNS = 6;
    private static final int PLANNER_CONTEXT_MAX_CHARS_PER_MSG = 200;

    /**
     * Builds a compact recent-conversation snippet for the planner so it can resolve
     * references/ellipsis (e.g. "再试试 / 它 / key") that an isolated single-turn task lacks.
     * Intentionally excludes long-term memory: it does not aid reference resolution and only
     * adds noise/tokens to the planning call.
     */
    private String buildPlannerContext(String sessionId) {
        List<Map<String, String>> history = memoryService.getHistory(sessionId);
        if (history == null || history.isEmpty()) {
            return "";
        }
        int from = Math.max(0, history.size() - PLANNER_CONTEXT_TURNS);
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> entry : history.subList(from, history.size())) {
            String content = entry.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            String role = "user".equals(entry.get("role")) ? "用户" : "助手";
            if (content.length() > PLANNER_CONTEXT_MAX_CHARS_PER_MSG) {
                content = content.substring(0, PLANNER_CONTEXT_MAX_CHARS_PER_MSG) + "…";
            }
            sb.append(role).append(": ").append(content).append("\n");
        }
        return sb.toString();
    }

    /**
     * <p>Publishes SSE events to {@code sink} in this order:
     * {@code connected → plan_thinking* → plan? → thinking* → step* → token* → done | error}
     * (see {@link ChatStreamEvent} for the canonical sequence).
     *
     * <p>This method always returns normally; errors are surfaced via an {@code error} event.
     * The caller must run this on a dedicated non-servlet thread and complete the
     * {@code SseEmitter} after this method returns.
     *
     * @param isCancelled supplier checked before each streamed chunk; when {@code true} the
     *                    Reactor pipeline is torn down early (client disconnected).
     */
    public void streamChat(String sessionId, String userMessage, String topicId,
                           Consumer<ChatStreamEvent> sink, BooleanSupplier isCancelled) {
        long start = System.currentTimeMillis();
        StringBuilder answer = new StringBuilder();
        StringBuilder thinkingBuffer = new StringBuilder();
        StringBuilder planThinkingBuffer = new StringBuilder();
        String[] finalFinishReason = new String[1];

        Consumer<AgentStep> stepEventPublisher = step -> sink.accept(ChatStreamEvent.step(sessionId, step));
        StepCollector.init(maxSteps, stepEventPublisher);
        StreamSinkHolder.set(sink);
        try {
            TaskPlanner.PlannerResult plannerResult = resolvePlan(sessionId, userMessage);
            List<PlanStep> plan = plannerResult.steps();

            String planReasoning = plannerResult.reasoningContent();
            if (planReasoning != null && !planReasoning.isBlank()) {
                planThinkingBuffer.append(planReasoning);
                sink.accept(ChatStreamEvent.planThinking(sessionId, planReasoning, planThinkingBuffer.length()));
            }

            if (!plan.isEmpty()) {
                sink.accept(ChatStreamEvent.plan(sessionId, plan, formatPlanSummary(plan)));
            }

            // 系统提示词 + 用户画像 + 相关记忆（top-k）
            // skills meta data + subagent description + plan description
            String systemPrompt = buildSystemPrompt(plan, topicId, userMessage);

            // 添加历史对话到上下文
            List<Message> history = buildHistory(sessionId);

            String[] toolNames = toolRegistry.getNames();

            log.info("[AI STREAM] --> session={}, planSteps={}, tools={}, historyMessages={}, userMsg={}",
                    sessionId, plan.size(), toolNames.length, history.size(),
                    userMessage.substring(0, Math.min(80, userMessage.length())));

            var promptSpec = chatClient.prompt()
                    .system(systemPrompt)
                    .messages(history)
                    .user(userMessage)
                    .toolNames(toolNames)
                    .stream()
                    .chatResponse()
                    .contextCapture() // 在当前 pipeline 订阅点主动把所有已注册 ThreadLocal 快照进 Reactor Context
                    .takeWhile(chunk -> !isCancelled.getAsBoolean())
                    .doOnNext(chunk -> {
                        String finishReason = extractFinishReason(chunk);
                        if (finishReason != null && !finishReason.isBlank()) {
                            finalFinishReason[0] = finishReason;
                        }
                        String reasoning = extractReasoning(chunk);
                        if (reasoning != null && !reasoning.isBlank()) {
                            thinkingBuffer.append(reasoning);
                            sink.accept(ChatStreamEvent.thinking(sessionId, reasoning, thinkingBuffer.length()));
                        }
                        String delta = extractDelta(chunk);
                        if (delta != null && !delta.isEmpty()) {
                            answer.append(delta);
                            sink.accept(ChatStreamEvent.token(sessionId, delta, answer.length()));
                        }
                        if ((reasoning != null && !reasoning.isBlank()) || (delta != null && !delta.isEmpty())) {
                            log.trace("[AI STREAM] chunk session={}, reasoningChars={}, answerChars={}, deltaChars={}",
                                    sessionId,
                                    reasoning != null ? reasoning.length() : 0,
                                    answer.length(),
                                    delta != null ? delta.length() : 0);
                        }
                    })
                    .blockLast();

            if (isCancelled.getAsBoolean()) {
                log.info("[AgentOrchestrator] stream cancelled by client, session={}", sessionId);
                return;
            }

            List<AgentStep> steps = StepCollector.collect();
            recordRagMetrics(steps);

            memoryService.addMessage(sessionId, defaultUserId, "user", userMessage);
            memoryService.addMessage(sessionId, defaultUserId, "assistant", answer.toString());

                log.info("[AgentOrchestrator] stream session={}, planSteps={}, toolCalls={}, finishReason={}, answerChars={}",
                    sessionId, plan.size(), steps.size(), finalFinishReason[0], answer.length());

            sink.accept(ChatStreamEvent.done(
                    sessionId, answer.toString(), steps, plan,
                    System.currentTimeMillis() - start, model));

        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String code;
            if (cause instanceof MaxStepsExceededException) {
                code = "MAX_STEPS_EXCEEDED";
            } else if (cause instanceof AiConfigurationException) {
                code = "AI_NOT_CONFIGURED";
            } else {
                code = "INTERNAL_ERROR";
                log.error("[AgentOrchestrator] streamChat error, session={}", sessionId, e);
            }
            sink.accept(ChatStreamEvent.error(sessionId, code, cause.getMessage()));
        } finally {
            StepCollector.clear();
            StreamSinkHolder.clear();
        }
    }

    private String extractDelta(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null) return null;
        var output = chunk.getResult().getOutput();
        if (output == null) return null;
        return output.getText();
    }

    private String extractFinishReason(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getMetadata() == null) return null;
        return chunk.getResult().getMetadata().getFinishReason();
    }

    private String extractReasoning(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null) return null;

        String fromGeneration = chunk.getResult().getMetadata().get("reasoningContent");
        if (fromGeneration != null && !fromGeneration.isBlank()) {
            return fromGeneration;
        }

        var output = chunk.getResult().getOutput();
        if (output == null || output.getMetadata() == null) {
            return null;
        }

        Object fromMessage = output.getMetadata().get("reasoningContent");
        return fromMessage instanceof String reasoning && !reasoning.isBlank() ? reasoning : null;
    }

    private void recordRagMetrics(List<AgentStep> steps) {
        long ragCalls = steps.stream()
                .filter(s -> KnowledgeSearchTool.class.getSimpleName().equals(s.toolName()))
                .count();
        ragCallsSummary.record(ragCalls);
    }

    private String formatPlanSummary(List<PlanStep> plan) {
        if (plan == null || plan.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plan.size(); i++) {
            if (i > 0) sb.append(" → ");
            sb.append("步骤").append(plan.get(i).step()).append(": ").append(plan.get(i).action());
        }
        return sb.toString();
    }
    private List<Message> buildHistory(String sessionId) {
        List<Map<String, String>> rawHistory = memoryService.getHistory(sessionId);
        List<Message> messages = new ArrayList<>();
        for (Map<String, String> entry : rawHistory) {
            String role = entry.get("role");
            String content = entry.get("content");
            if ("user".equals(role)) {
                messages.add(new UserMessage(content));
            } else if ("assistant".equals(role)) {
                messages.add(new AssistantMessage(content));
            }
        }
        return messages;
    }

    /**
    * Builds the system prompt for the streaming ReAct path.
     * Includes the execution plan, plan-enforcement directive, and max-steps constraint.
     */
    private String buildSystemPrompt(List<PlanStep> plan, String topicId, String userQuery) {
        String profileSection = userProfileService.formatForSystemPrompt(defaultUserId); // 用户画像
        String memorySection = formatMemories(defaultUserId, userQuery); // 相关记忆，top-k
        String topicSection = (topicId != null && !topicId.isBlank())
                ? String.format("%n%n【研究主题】你当前在帮助用户研究主题：%s。" +
                  "调用 KnowledgeSearchTool 时，topicId 参数必须使用 \"%s\"。", topicId, topicId)
                : "";
        return baseSystemPrompt
                + profileSection
                + memorySection
                + topicSection
                + formatSkills()
                + formatSubAgents()
                + formatPlanGuidance(plan)
                + String.format("%n请在回复中简短说明每次工具调用的原因。最多调用工具 %d 次。", maxSteps);
    }

    private String formatPlanGuidance(List<PlanStep> plan) {
        if (plan == null || plan.isEmpty()) {
            return "";
        }
        if (isDirectAnswerPlan(plan)) {
            return "\n\n【执行策略】规划器初步判断本轮很可能无需调用工具，请优先基于上下文与自身知识直接回答。" +
                   "仅当你确认确实需要外部最新信息或私有资料、且能明确说出要查什么时，才调用对应工具；" +
                   "不要为验证猜测而反复试探工具。";
        }
        if (!hasActionablePlanStep(plan)) {
            return "";
        }

        StringBuilder sb = new StringBuilder("\n\n【执行计划】\n");
        appendActionablePlanSteps(sb, plan);
        sb.append("\n【执行约束】请优先按上方【执行计划】调用对应工具，并以工具结果为主要依据作答。")
                    .append("knowledgeSearchTool 返回 docsFound=0 时，不要重复检索同类问题；")
                    .append("若问题需要最新、当前、版本号、发布日期、官方资料或外部公开事实，请改用 webTool。")
                    .append("当工具无结果或信息不足时，结合你自身的知识把答案补全，并简要说明依据来源。");
        return sb.toString();
    }

    private void appendActionablePlanSteps(StringBuilder sb, List<PlanStep> plan) {
        for (PlanStep step : plan) {
            if ("finish".equals(step.action())) {
                continue;
            }
            sb.append(step.step())
                    .append(". [").append(step.action()).append("] ")
                    .append(step.reason()).append("\n");
        }
    }

    private boolean isDirectAnswerPlan(List<PlanStep> plan) {
        return plan != null
                && !plan.isEmpty()
                && plan.stream().allMatch(step -> "finish".equals(step.action()));
    }

    private boolean hasActionablePlanStep(List<PlanStep> plan) {
        return plan != null && plan.stream().anyMatch(step -> !"finish".equals(step.action()));
    }

    /**
     * 列出当前可派发的 sub-agent 类型，并给出判断准则。
     * 与 {@link #formatSkills()} 同源（progressive disclosure / 注册表驱动），
     * 在 {@link SubAgentRegistry} 为空时返回空串，不污染 prompt。
     */
    private String formatSubAgents() {
        if (subAgentRegistry.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## 可派发的子 Agent (Sub-Agent)\n")
                .append("调用 `dispatchSubAgentTool(subagentType, taskDescription)` 把深度调研/长文档分析这类'重活'派给隔离上下文的子 Agent。\n")
                .append("\n判断准则：\n")
                .append("- ✅ 适合派：需要多轮检索 / 多角度分析 / 长文档综合，单 Agent 上下文会被噪声淹没\n")
                .append("- ❌ 不要派：单次 knowledge_search 1-2 次能搞定的简单问题；用专用工具就够的\n")
                .append("- ❌ 不要派：凭自身知识就能准确回答的常识问题，或已多次检索知识库均无结果（库中无此内容，再派也是空转）\n")
                .append("\n约束：单次对话最多派 ").append(maxSubAgentDispatches).append(" 次；")
                .append("taskDescription 必须自包含（子 Agent 看不到对话历史）；子 Agent 返回 status=PARTIAL_SUCCESS 时基于已有信息判断是否够用。\n\n")
                .append("可用类型：\n");
        for (SubAgentDefinition def : subAgentRegistry.list()) {
            sb.append("- **").append(def.type()).append("**：");
            String firstLine = def.systemPrompt().lines().findFirst().orElse(def.type());
            sb.append(firstLine).append("\n");
        }
        return sb.toString();
    }

    /**
     * 列出所有可用 Skill 的 name + description（progressive disclosure 第一层）。
     * 模型据此判断是否调用 {@code loadSkillTool} 加载某个 skill 的完整指令。
     * 若无可用 skill 则返回空串，不污染 prompt。
     */
    private String formatSkills() {
        Collection<Skill> all = skillRegistry.list();
        if (all.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## 可用 Skills\n")
                .append("仅当下方某个 skill 的 name 和 description 明确匹配当前任务时，")
                .append("才调用 `loadSkillTool(name)` 加载完整指令；")
                .append("需要 skill 的内嵌资源时调用 `readSkillResourceTool(skill, path)`。")
                .append("只能使用下方列出的 skill name，不要发明或猜测不存在的 skill。\n\n");
        for (Skill s : all) {
            sb.append("- **").append(s.manifest().name()).append("**: ")
              .append(s.manifest().description()).append("\n");
        }
        return sb.toString();
    }

    private String formatMemories(String userId, String query) {
        try {
            // 仅注入 PROCEDURAL（长期偏好/习惯）与 SEMANTIC（事实），按配额分配 topK；
            // EPISODIC（对话摘要）是反思的中间产物，不进主 prompt，避免长文本挤占名额。
            log.debug("系统提示词 - 记忆:  PROCEDURAL（长期偏好/习惯）与 SEMANTIC（事实），按配额分配 topK");
            List<MemoryManager.MemorySearchResult> memories = new ArrayList<>();
            memories.addAll(memoryManager.search(userId, query, proceduralTopK, MemoryType.PROCEDURAL));
            memories.addAll(memoryManager.search(userId, query, semanticTopK, MemoryType.SEMANTIC));
            if (memories.isEmpty()) {
                log.debug("系统提示词 - 记忆为空");
                return "";
            }
            StringBuilder sb = new StringBuilder("\n\n【相关记忆】\n");
            for (MemoryManager.MemorySearchResult mem : memories) {
                sb.append("- ").append(mem.content()).append("\n");
            }

            log.debug("系统提示词 - 记忆:{}", sb);
            return sb.toString();
        } catch (Exception e) {
            log.debug("[AgentOrchestrator] Failed to fetch memories for user={}: {}", userId, e.getMessage());
            return "";
        }
    }

}
