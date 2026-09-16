package com.dawn.ai.agent.orchestration;

import com.dawn.ai.exception.AiConfigurationException;
import com.dawn.ai.exception.MaxStepsExceededException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 针对 {@link AgentOrchestrator#streamErrorCode(Throwable)} 的纯映射测试 —— 这个方法
 * 决定一条失败的 stream 最终发出哪个 SSE 错误码。
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
        // 就算直接 cause 是 MaxStepsExceededException，只要 cause 链更深处有 circuit open，还是它优先
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
        // cause == this 时 Throwable.getCause() 会返回 null；这里用子类重写来模拟一个环
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
