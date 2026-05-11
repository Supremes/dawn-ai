package com.dawn.ai.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.resources.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires dawn-ai's existing per-thread sessionId into Spring AI's
 * Micrometer Observations so Langfuse can group traces by chat session,
 * and labels every exported span with a process-wide environment tag.
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
}
