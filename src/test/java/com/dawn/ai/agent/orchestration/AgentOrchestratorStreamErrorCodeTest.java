package com.dawn.ai.agent.orchestration;

import com.dawn.ai.exception.AiConfigurationException;
import com.dawn.ai.exception.MaxStepsExceededException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure mapping test for {@link AgentOrchestrator#streamErrorCode(Throwable)} — the function
 * that decides which SSE error code a failed stream emits.
 */
class AgentOrchestratorStreamErrorCodeTest {

    private static CallNotPermittedException circuitOpen() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("llmChat");
        breaker.transitionToOpenState();
        return CallNotPermittedException.createCallNotPermittedException(breaker);
    }

    @Test
    void directCallNotPermitted_mapsToLlmUnavailable() {
        assertThat(AgentOrchestrator.streamErrorCode(circuitOpen())).isEqualTo("LLM_UNAVAILABLE");
    }

    @Test
    void wrappedCallNotPermitted_mapsToLlmUnavailable() {
        Throwable wrapped = new RuntimeException("reactor wrapper",
                new IllegalStateException("spring ai wrapper", circuitOpen()));
        assertThat(AgentOrchestrator.streamErrorCode(wrapped)).isEqualTo("LLM_UNAVAILABLE");
    }

    @Test
    void llmUnavailable_takesPrecedenceOverOtherCauseMappings() {
        // even if the direct cause is a MaxStepsExceededException, a circuit-open deeper in the chain wins
        MaxStepsExceededException maxSteps = new MaxStepsExceededException("max");
        maxSteps.initCause(circuitOpen());
        Throwable wrapped = new RuntimeException(maxSteps);
        assertThat(AgentOrchestrator.streamErrorCode(wrapped)).isEqualTo("LLM_UNAVAILABLE");
    }

    @Test
    void maxStepsExceeded_mapsToMaxStepsExceeded() {
        assertThat(AgentOrchestrator.streamErrorCode(new RuntimeException(new MaxStepsExceededException("max"))))
                .isEqualTo("MAX_STEPS_EXCEEDED");
        assertThat(AgentOrchestrator.streamErrorCode(new MaxStepsExceededException("max")))
                .isEqualTo("MAX_STEPS_EXCEEDED");
    }

    @Test
    void aiConfiguration_mapsToAiNotConfigured() {
        assertThat(AgentOrchestrator.streamErrorCode(new RuntimeException(new AiConfigurationException("no key"))))
                .isEqualTo("AI_NOT_CONFIGURED");
    }

    @Test
    void anythingElse_mapsToInternalError() {
        assertThat(AgentOrchestrator.streamErrorCode(new RuntimeException(new IOException("boom"))))
                .isEqualTo("INTERNAL_ERROR");
        assertThat(AgentOrchestrator.streamErrorCode(new IllegalStateException("boom")))
                .isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void selfReferencingCause_doesNotLoopForever() {
        // Throwable.getCause() returns null while cause == this; emulate a cycle via a subclass override
        Throwable loop = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(AgentOrchestrator.streamErrorCode(loop)).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void llmUnavailableMessage_isHumanReadableAndNonBlank() {
        assertThat(AgentOrchestrator.LLM_UNAVAILABLE_MESSAGE).isNotBlank();
    }
}
