package com.dawn.ai.config;

import com.dawn.ai.agent.tools.CalculatorTool;
import com.dawn.ai.agent.tools.WeatherTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.openai.OpenAITextEmbedding;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.store.PgVectorStore;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String modelName;

    @Value("${app.ai.openai.embedding.api-key}")
    private String embeddingApiKey;

    @Value("${app.ai.openai.embedding.base-url:https://api.openai.com}")
    private String embeddingBaseURL;

    @Value("${app.ai.openai.embedding.options.model:Qwen/Qwen3-Embedding-8B}")
    private String embeddingModelName;

    @Value("${app.ai.openai.embedding.options.dimensions:1536}")
    private int dimensions;

    @Value("${spring.datasource.url}")
    private String jdbcURL;
    
    @Value("${spring.datasource.username}")
    private String jdbcUsername;
    
    @Value("${spring.datasource.password}")
    private String jdbcPassword;

    @Bean
    public OpenAIChatModel agentScopeModel() {
        log.info("[AgentScopeConfig] Initializing OpenAIChatModel: model={}, baseUrl={}",
                modelName, baseUrl);
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .build();
    }

    @Bean
    public EmbeddingModel agentScopeEmbeddingModel(ObjectMapper objectMapper) {
        log.info("[AgentScopeConfig] Initializing compatible embedding model: model={}, baseUrl={}",
                embeddingModelName, embeddingBaseURL);
        return OpenAITextEmbedding.builder()
                .baseUrl(embeddingBaseURL)
                .modelName(embeddingModelName)
                .dimensions(dimensions)
                .apiKey(embeddingApiKey)
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
                                     CalculatorTool calculatorTool) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(weatherTool);
        toolkit.registerTool(calculatorTool);
                log.info("[AgentScopeConfig] Toolkit initialized with {} tools: weatherTool, calculatorTool",
                toolkit.getToolNames().size());
        return toolkit;
    }

    @Bean(destroyMethod = "close")
    public PgVectorStore pgVectorStore() {
        try {
            return PgVectorStore.builder()
                    .jdbcUrl(jdbcURL)
                    .username(jdbcUsername)
                    .password(jdbcPassword)
                    .tableName("vector_store_BAAI_bge_m3")
                    .dimensions(dimensions)
                    .distanceType(PgVectorStore.DistanceType.COSINE)
                    .build();
        } catch (VectorStoreException e) {
            log.error("PgVectorStore initialization failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize PgVectorStore", e);
        }
    }

    // 不指定名字，默认以方法名作为bean的名称
    @Bean
    @Primary
    public Knowledge simpleKnowledge(PgVectorStore pgVectorStore, EmbeddingModel agentScopeEmbeddingModel) {
        log.info("[AgentScopeConfig] Initializing simpleKnowledge.");

        return SimpleKnowledge.builder()
                .embeddingModel(agentScopeEmbeddingModel)
                .embeddingStore(pgVectorStore)
                .build();
    }

//    @Bean
//    public Knowledge ragFlowKnowledge() {
//        log.info("[AgentScopeConfig] Initializing ragFlowKnowledge.");
//        return RAGFlowKnowledge.builder()
//                .config(RAGFlowConfig.builder().build())
//                .build();
//    }
}
