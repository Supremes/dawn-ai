package com.dawn.ai.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseObservationConfigTest {

    private final LangfuseObservationConfig config = new LangfuseObservationConfig();

    @AfterEach
    void clear() {
        AiInteractionContext.clear();
    }

    @Test
    void filterEmitsSessionIdWhenContextHasOne() {
        AiInteractionContext.setSessionId("sess-123");
        ObservationFilter filter = config.langfuseSessionFilter();

        Observation.Context ctx = newContext();
        filter.map(ctx);

        assertThat(ctx.getLowCardinalityKeyValues())
                .contains(KeyValue.of("session.id", "sess-123"));
    }

    @Test
    void filterEmitsNothingWhenContextEmpty() {
        ObservationFilter filter = config.langfuseSessionFilter();

        Observation.Context ctx = newContext();
        filter.map(ctx);

        assertThat(ctx.getLowCardinalityKeyValues())
                .noneMatch(kv -> kv.getKey().equals("session.id"));
    }

    @Test
    void filterIgnoresBlankSessionId() {
        AiInteractionContext.setSessionId("   ");
        ObservationFilter filter = config.langfuseSessionFilter();

        Observation.Context ctx = newContext();
        filter.map(ctx);

        assertThat(ctx.getLowCardinalityKeyValues())
                .noneMatch(kv -> kv.getKey().equals("session.id"));
    }

    private Observation.Context newContext() {
        Observation.Context ctx = new Observation.Context();
        ctx.setName("test.observation");
        return ctx;
    }
}
