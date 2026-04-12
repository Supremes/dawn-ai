package com.dawn.ai.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPackageStructureTest {

    @Test
    @DisplayName("agent 内核类应按职责拆到子包")
    void agentCoreShouldLiveInDedicatedSubpackages() {
        assertThatCode(() -> Class.forName("com.dawn.ai.agent.orchestration.AgentOrchestrator")).doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.dawn.ai.agent.orchestration.AgentResult")).doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.dawn.ai.agent.planning.PlanStep")).doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.dawn.ai.agent.planning.TaskPlanner")).doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.dawn.ai.agent.trace.StepCollector")).doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.dawn.ai.agent.trace.AgentStep")).doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.dawn.ai.agent.trace.ToolExecutionAspect")).doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("com.dawn.ai.agent.registry.ToolRegistry")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rag 应保持顶层 sibling package，不应并入 agent")
    void ragShouldRemainSiblingPackage() {
        assertThatCode(() -> Class.forName("com.dawn.ai.rag.RagService")).doesNotThrowAnyException();
        assertThatThrownBy(() -> Class.forName("com.dawn.ai.agent.rag.RagService"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.dawn.ai.agent.AgentOrchestrator"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
