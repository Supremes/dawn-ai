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
            PromptManager promptManager,
            @Value("${app.ai.subagent.research.max-steps:15}") int maxSteps,
            @Value("${app.ai.subagent.research.timeout-seconds:60}") int timeoutSeconds,
            @Value("${app.ai.subagent.research.model-override:#{null}}") String modelOverride,
            @Value("${app.ai.subagent.research.temperature-override:#{null}}") Double temperatureOverride) {

        String systemPrompt = promptManager.render("research-subagent");

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
