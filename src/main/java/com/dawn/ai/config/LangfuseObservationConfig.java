package com.dawn.ai.config;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.ObservationFilter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.resources.Resource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.stream.Collectors;

/**
 * 将 dawn-ai 现有的线程级 sessionId 注入 Spring AI 的 Micrometer Observation 体系，
 * 使 Langfuse 能够按会话维度聚合 Trace；同时为每个导出 Span 打上进程级环境标签，
 * 并在每次 LLM Observation 上附加完整的 prompt 和 completion 文本。
 *
 * <p>为何需要这个配置类：
 * <ol>
 *   <li>Spring AI 内置的 {@code spring.ai.chat.observations.log-prompt=true} 只把
 *       prompt 写入 SLF4J 日志，不会注入到 OTel Span 的 Attribute 中，Langfuse
 *       因此看不到 Input/Output 内容，必须通过自定义 Convention 手动补齐。</li>
 *   <li>Langfuse 的 Sessions 视图依赖 OTel Attribute {@code session.id}，但
 *       Spring AI 默认不传播任何会话标识，需要通过 ObservationFilter 在每个
 *       Span 写入时从线程上下文中读取并注入。</li>
 *   <li>Langfuse 的多环境隔离（dev/staging/prod）依赖 OTel Resource Attribute
 *       {@code langfuse.environment}，这属于进程级静态元数据，应在 SDK
 *       初始化阶段一次性写入 Resource，而不是每个 Span 重复携带。</li>
 * </ol>
 */
@Configuration
public class LangfuseObservationConfig {

    /**
     * 【为何需要此 Bean】
     * Spring AI 的 Observation 不感知业务层的会话概念，所有 LLM 调用在 Langfuse
     * 中默认是孤立 Trace，无法按用户会话聚合查看对话历史。
     *
        * <p>此过滤器在每个 Span 写出前从当前线程（已由 {@link AiInteractionContext}
        * 跨 Reactor/线程池边界传播）读取 sessionId，注入为高基数 KeyValue
        * {@code langfuse.session.id}。这是 Langfuse OTel 协议中驱动 Sessions 视图的
        * 官方属性；使用高基数可避免每个会话在 Prometheus 中生成独立时序。
     */
    @Bean
    public ObservationFilter langfuseSessionFilter() {
        return ctx -> {
            String sid = AiInteractionContext.getSessionId();
            if (sid != null && !sid.isBlank()) {
                ctx.addHighCardinalityKeyValue(KeyValue.of("langfuse.session.id", sid));
            }
            return ctx;
        };
    }

    /**
     * 【为何需要此 Bean】
     * Langfuse 支持多环境隔离，通过 OTel Resource Attribute {@code langfuse.environment}
     * 区分 dev / staging / prod 的数据，避免测试流量污染生产看板。
     *
     * <p>Resource Attribute 是进程级静态元数据，在 OTel SDK 初始化时写入一次即可，
     * 后续所有 Span 导出时都会自动携带，无需在每个 Span 上重复添加（与
     * Span Attribute 相比可显著减少网络传输和 ClickHouse 存储开销）。
     * 通过 {@link AutoConfigurationCustomizerProvider} 钩子合并到全局 Resource 中，
     * 是 OpenTelemetry Java Agent / SDK 的标准扩展点。
     */
    @Bean
    public AutoConfigurationCustomizerProvider langfuseResourceCustomizer(
            @Value("${langfuse.environment:dev}") String env) {
        return customizer -> customizer.addResourceCustomizer((resource, props) ->
                resource.merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("langfuse.environment"), env))));
    }

    /**
     * 【为何需要重写此 Bean】
     * Spring AI 默认的 {@link DefaultChatModelObservationConvention} 出于隐私
     * 和性能考虑，不会将 prompt 文本和 completion 文本写入 OTel Span Attribute，
     * 导致 Langfuse 的 Trace 详情页只能看到 token 用量和模型名称，看不到
     * 实际的对话内容，严重影响可观测性和调试效率。
     *
     * <p>重写原因分解：
     * <ul>
     *   <li>{@code gen_ai.prompt}：GenAI 语义约定标准属性，Langfuse v3 读取此字段
     *       渲染 Trace 的 Input 面板，不注入则 Input 为空。</li>
     *   <li>{@code gen_ai.completion}：对应 Trace 的 Output 面板，不注入则
     *       无法在 Langfuse 中直接查看模型回复内容。</li>
     *   <li>作为高基数（High Cardinality）KeyValue 注入：prompt/completion 文本
     *       内容各不相同，属于高基数数据，应使用 {@code getHighCardinalityKeyValues}
     *       而非低基数接口，符合 Micrometer Observation 的语义分层规范。</li>
     * </ul>
     */
    @Bean
    public ChatModelObservationConvention langfuseChatModelObservationConvention() {
        return new DefaultChatModelObservationConvention() {
            @Override
            public KeyValues getHighCardinalityKeyValues(ChatModelObservationContext ctx) {
                KeyValues kvs = super.getHighCardinalityKeyValues(ctx);
                Prompt request = ctx.getRequest();
                if (request != null && request.getInstructions() != null) {
                    String prompt = request.getInstructions().stream()
                            .map(Message::getText)
                            .filter(s -> s != null && !s.isEmpty())
                            .collect(Collectors.joining("\n"));
                    if (!prompt.isEmpty()) {
                        kvs = kvs.and("gen_ai.prompt", prompt);
                    }
                }
                ChatResponse response = ctx.getResponse();
                if (response != null && response.getResults() != null) {
                    String completion = response.getResults().stream()
                            .map(Generation::getOutput)
                            .filter(o -> o != null)
                            .map(o -> o.getText())
                            .filter(s -> s != null && !s.isEmpty())
                            .collect(Collectors.joining("\n"));
                    if (!completion.isEmpty()) {
                        kvs = kvs.and("gen_ai.completion", completion);
                    }
                }
                return kvs;
            }
        };
    }
}
