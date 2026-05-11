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
 * Wires dawn-ai's existing per-thread sessionId into Spring AI's
 * Micrometer Observations so Langfuse can group traces by chat session,
 * labels every exported span with a process-wide environment tag, and
 * surfaces full chat prompt + completion text on each LLM observation
 * (Spring AI's built-in {@code log-prompt} switch only writes to SLF4J
 * and never reaches the OTel span).
 */
@Configuration
public class LangfuseObservationConfig {

    /**
     * Per-span filter: stamps {@code session.id} on every Observation when
     * a sessionId is present on the current thread (already propagated by
     * {@link AiInteractionContext} across Reactor / executor handoffs).
     * {@code session.id} is the documented Langfuse OTel attribute that drives
     * the Sessions view.
     */
    @Bean
    public ObservationFilter langfuseSessionFilter() {
        return ctx -> {
            String sid = AiInteractionContext.getSessionId();
            if (sid != null && !sid.isBlank()) {
                ctx.addLowCardinalityKeyValue(KeyValue.of("session.id", sid));
            }
            return ctx;
        };
    }

    /**
     * Process-wide OTel resource attribute. Set once at SDK init rather than
     * per-span so it doesn't bloat every span payload.
     */
    @Bean
    public AutoConfigurationCustomizerProvider langfuseResourceCustomizer(
            @Value("${langfuse.environment:dev}") String env) {
        return customizer -> customizer.addResourceCustomizer((resource, props) ->
                resource.merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("langfuse.environment"), env))));
    }

    /**
     * Adds {@code gen_ai.prompt} and {@code gen_ai.completion} as
     * high-cardinality KeyValues so the OTLP exporter ships full prompt and
     * completion text. Langfuse v3 reads these GenAI semantic-convention
     * attributes and renders them as the observation's input/output.
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
