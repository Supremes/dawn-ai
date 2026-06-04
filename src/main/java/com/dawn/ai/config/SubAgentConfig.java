package com.dawn.ai.config;

import com.dawn.ai.agent.subagent.SubAgentDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Sub-agent 类型注册与运行时资源配置。
 *
 * <p>每个 {@link SubAgentDefinition} bean 由 {@link com.dawn.ai.agent.subagent.SubAgentRegistry}
 * 自动收集，按 {@code type} 名建立索引。
 */
@Slf4j
@Configuration
public class SubAgentConfig {

    /**
     * Research sub-agent：专注深度知识库检索 + 综合。
     * 主 Agent 在调研类任务里通过 {@code dispatchSubAgentTool(type=research, ...)} 派发。
     */
    @Bean
    public SubAgentDefinition researchSubAgentDefinition(
            @Value("${app.ai.subagent.research.max-steps:15}") int maxSteps,
            @Value("${app.ai.subagent.research.timeout-seconds:60}") int timeoutSeconds,
            @Value("${app.ai.subagent.research.model-override:#{null}}") String modelOverride,
            @Value("${app.ai.subagent.research.temperature-override:#{null}}") Double temperatureOverride) {

        String systemPrompt = """
                你是 dawn-ai 的研究子 Agent，专注于深度信息检索与综合，由主 Agent 派发任务给你。

                工作准则：
                1. 优先从多角度多关键词调用 knowledgeSearchTool 检索知识库，单次结果不足时换角度继续。
                2. 必要时通过 loadSkillTool / readSkillResourceTool 加载领域内的 skill 指令辅助分析。
                3. 你看不到主 Agent 的对话历史；任务描述里没说的就当不知道，不要凭空推断上下文。
                4. 最终回答要求：
                   - 结构化：要点 / 段落 / 列表清晰，便于主 Agent 直接复用
                   - 有依据：标注信息来源（哪一步检索得到、对应文档片段编号）
                   - 自包含：主 Agent 看不到你的中间步骤，结论必须能独立成段
                5. 不要再调用 dispatch_subagent（你已经是子 Agent，禁止递归派发）。
                """;

        Set<String> allowedTools = new LinkedHashSet<>();
        allowedTools.add("knowledgeSearchTool");
        allowedTools.add("loadSkillTool");
        allowedTools.add("readSkillResourceTool");

        SubAgentDefinition def = new SubAgentDefinition(
                "research",
                systemPrompt,
                allowedTools,
                maxSteps,
                timeoutSeconds,
                modelOverride,
                temperatureOverride);

        log.info("[SubAgentConfig] registered research sub-agent: maxSteps={}, timeout={}s, model={}",
                maxSteps, timeoutSeconds, modelOverride != null ? modelOverride : "(default)");
        return def;
    }

    /**
     * Sub-agent 专用线程池。与 chatStreamExecutor / ragRetrievalExecutor 隔离，
     * 防止 sub-agent 占满 SSE 流式工作线程导致新对话排队。
     *
     * <p>{@link ThreadPoolExecutor.AbortPolicy}：饱和即 fail-fast，
     * 由 DispatchSubAgentTool 转成 FAILED 结果，主 Agent LLM 自行决定降级路径。
     */
    @Bean(name = "subAgentExecutor", destroyMethod = "shutdown")
    public ExecutorService subAgentExecutor() {
        return new ThreadPoolExecutor(
                2, 8,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(16),
                r -> {
                    Thread t = new Thread(r, "sub-agent-" + System.nanoTime());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
