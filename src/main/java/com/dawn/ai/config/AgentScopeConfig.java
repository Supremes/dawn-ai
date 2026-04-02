package com.dawn.ai.config;

import com.dawn.ai.agent.tools.CalculatorTool;
import com.dawn.ai.agent.tools.KnowledgeSearchTool;
import com.dawn.ai.agent.tools.WeatherTool;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope-Java configuration.
 *
 * Provides:
 * - {@link OpenAIChatModel} — AgentScope model wrapping the same OpenAI endpoint
 * - {@link Toolkit} — container of all @Tool-annotated beans, shared across requests
 *
 * Reuses the same YAML properties as Spring AI to avoid duplicating config.
 */
@Slf4j
@Configuration
public class AgentScopeConfig {

    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String modelName;

    @Bean
    public OpenAIChatModel agentScopeModel() {
        log.info("[AgentScopeConfig] Initializing OpenAIChatModel: model={}, baseUrl={}",
                modelName, baseUrl);
        return OpenAIChatModel.builder()
                .apiKey(openAiApiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Shared Toolkit containing all agent tools registered via {@link io.agentscope.core.tool.Tool}.
     *
     * New tools: add @Tool method to the tool class + register here.
     * The Toolkit is thread-safe and shared across all request contexts.
     */
    @Bean
    public Toolkit agentScopeToolkit(WeatherTool weatherTool,
                                     CalculatorTool calculatorTool,
                                     KnowledgeSearchTool knowledgeSearchTool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(weatherTool);
        toolkit.registerTool(calculatorTool);
        toolkit.registerTool(knowledgeSearchTool);
        log.info("[AgentScopeConfig] Toolkit initialized with {} tools: weatherTool, calculatorTool, knowledgeSearchTool",
                toolkit.getToolNames().size());
        return toolkit;
    }
}
