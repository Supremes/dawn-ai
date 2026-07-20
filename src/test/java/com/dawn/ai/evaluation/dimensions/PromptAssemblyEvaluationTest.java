package com.dawn.ai.evaluation.dimensions;

import com.dawn.ai.agent.orchestration.AgentOrchestrator;
import com.dawn.ai.agent.planning.PlanStep;
import com.dawn.ai.agent.planning.TaskPlanner;
import com.dawn.ai.agent.registry.ToolRegistry;
import com.dawn.ai.agent.skill.Skill;
import com.dawn.ai.agent.skill.SkillManifest;
import com.dawn.ai.agent.skill.SkillRegistry;
import com.dawn.ai.agent.subagent.SubAgentDefinition;
import com.dawn.ai.agent.subagent.SubAgentRegistry;
import com.dawn.ai.agent.token.TokenWindowManager;
import com.dawn.ai.evaluation.base.EvaluationCase;
import com.dawn.ai.evaluation.base.EvaluationDatasetLoader;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.memory.MemoryManager;
import com.dawn.ai.memory.MemoryType;
import com.dawn.ai.memory.UserProfileService;
import com.dawn.ai.service.MemoryService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptAssemblyEvaluationTest {

    private static final int DEFAULT_MAX_STEPS = 10;
    private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+)");

    private AgentOrchestrator agentOrchestrator;
    private UserProfileService userProfileService;
    private SkillRegistry skillRegistry;
    private SubAgentRegistry subAgentRegistry;

    @Test
    @DisplayName("evaluation: Prompt 拼装正确性")
    void evaluate_promptAssembly() throws Exception {
        agentOrchestrator = newAgentOrchestrator();
        List<EvaluationCase> cases = EvaluationDatasetLoader.loadByDimension(JudgeDimension.PROMPT_ASSEMBLY.id());
        assertThat(cases).isNotEmpty();

        Method method = AgentOrchestrator.class.getDeclaredMethod(
                "buildSystemPrompt", List.class, String.class, String.class);
        method.setAccessible(true);

        for (EvaluationCase evalCase : cases) {
            @SuppressWarnings("unchecked")
            Map<String, String> promptSegments = (Map<String, String>) evalCase.context().get("promptSegments");
            assertThat(promptSegments)
                    .as("promptSegments must exist for case '%s'", evalCase.id())
                    .isNotNull();

            arrangePromptDependencies(promptSegments);

            List<PlanStep> plan = derivePlan(promptSegments);
            String topicId = blankToNull(promptSegments.get("topic"));

            String actualPrompt = (String) method.invoke(
                    agentOrchestrator, plan, topicId, evalCase.query());

            assertPresentSegments(evalCase, promptSegments, actualPrompt);
            assertAbsentSegments(evalCase, promptSegments, actualPrompt);
        }
    }

    private AgentOrchestrator newAgentOrchestrator() {
        userProfileService = mock(UserProfileService.class);
        skillRegistry = mock(SkillRegistry.class);
        subAgentRegistry = mock(SubAgentRegistry.class);
        MemoryManager memoryManager = mock(MemoryManager.class);
        TokenWindowManager tokenWindowManager = mock(TokenWindowManager.class);

        when(memoryManager.search(anyString(), anyString(), anyInt(), any(MemoryType.class)))
                .thenReturn(List.of());
        when(tokenWindowManager.truncateToTokenBudget(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(tokenWindowManager.getMaxMemoryTokens()).thenReturn(1000);
        when(tokenWindowManager.getMaxSkillsTokens()).thenReturn(1000);

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                mock(ChatClient.class),
                mock(MemoryService.class),
                memoryManager,
                mock(TaskPlanner.class),
                mock(ToolRegistry.class),
                mock(MeterRegistry.class),
                userProfileService,
                skillRegistry,
                subAgentRegistry,
                tokenWindowManager,
                Optional.empty());
        ReflectionTestUtils.setField(orchestrator, "defaultUserId", "evaluation-user");
        ReflectionTestUtils.setField(orchestrator, "proceduralTopK", 2);
        ReflectionTestUtils.setField(orchestrator, "semanticTopK", 2);
        ReflectionTestUtils.setField(orchestrator, "maxSubAgentDispatches", 3);
        return orchestrator;
    }

    private void arrangePromptDependencies(Map<String, String> segments) {
        ReflectionTestUtils.setField(agentOrchestrator, "baseSystemPrompt",
                nonBlankOrDefault(segments.get("base"), "Dawn AI"));
        ReflectionTestUtils.setField(agentOrchestrator, "maxSteps",
                maxStepsFrom(segments.get("tool")));

        String userProfile = blankToNull(segments.get("userProfile"));
        when(userProfileService.formatForSystemPrompt(anyString()))
                .thenReturn(userProfile == null ? "" : "\n\n【用户画像】\n" + userProfile + "\n");

        String skills = blankToNull(segments.get("skills"));
        when(skillRegistry.list()).thenReturn(skills == null
                ? List.of()
                : List.of(new Skill(new SkillManifest(skills, "evaluation fixture", Map.of()),
                        "", null, Skill.Source.BUILTIN)));

        String subAgent = blankToNull(segments.get("subAgent"));
        when(subAgentRegistry.isEmpty()).thenReturn(subAgent == null);
        when(subAgentRegistry.list()).thenReturn(subAgent == null
                ? List.of()
                : List.of(new SubAgentDefinition(
                        "research", subAgent, Set.of("knowledgeSearchTool"), 3, 30, null, null)));
    }

    private static void assertPresentSegments(
            EvaluationCase evalCase,
            Map<String, String> segments,
            String actualPrompt) {
        containsIfPresent(evalCase, actualPrompt, "base", segments.get("base"));
        containsIfPresent(evalCase, actualPrompt, "skills", segments.get("skills"));
        containsIfPresent(evalCase, actualPrompt, "subAgent", segments.get("subAgent"));
        containsIfPresent(evalCase, actualPrompt, "topic", segments.get("topic"));
        containsIfPresent(evalCase, actualPrompt, "userProfile", segments.get("userProfile"));

        String plan = blankToNull(segments.get("plan"));
        if (plan != null) {
            assertThat(actualPrompt)
                    .as("case '%s' should contain plan contract", evalCase.id())
                    .contains(plan);
        }

        String enforcement = blankToNull(segments.get("enforcement"));
        if (plan != null && enforcement != null) {
            assertThat(actualPrompt)
                    .as("case '%s' should contain enforcement contract tied to a real plan", evalCase.id())
                    .contains(enforcement);
        }

        String tool = blankToNull(segments.get("tool"));
        if (tool != null) {
            assertThat(actualPrompt)
                    .as("case '%s' should contain tool-call limit", evalCase.id())
                    .contains("最多调用工具 " + maxStepsFrom(tool) + " 次");
        }
    }

    private static void assertAbsentSegments(
            EvaluationCase evalCase,
            Map<String, String> segments,
            String actualPrompt) {
        if (blankToNull(segments.get("skills")) == null) {
            assertThat(actualPrompt)
                    .as("case '%s' should not contain the skills catalog section", evalCase.id())
                    .doesNotContain("## 可用 Skills");
        }
        if (blankToNull(segments.get("subAgent")) == null) {
            assertThat(actualPrompt)
                    .as("case '%s' should not contain the sub-agent catalog section", evalCase.id())
                    .doesNotContain("## 可派发的子 Agent");
        }
        if (blankToNull(segments.get("plan")) == null) {
            assertThat(actualPrompt)
                    .as("case '%s' should not contain plan-only sections", evalCase.id())
                    .doesNotContain("【执行计划】", "【执行策略】");
        }
        if (blankToNull(segments.get("topic")) == null) {
            assertThat(actualPrompt)
                    .as("case '%s' should not contain a topic section", evalCase.id())
                    .doesNotContain("【研究主题】");
        }
        if (blankToNull(segments.get("userProfile")) == null) {
            assertThat(actualPrompt)
                    .as("case '%s' should not contain a user profile section", evalCase.id())
                    .doesNotContain("【用户画像】");
        }
        assertThat(actualPrompt)
                .as("case '%s' should never render a literal null", evalCase.id())
                .doesNotContain("null");
    }

    private static List<PlanStep> derivePlan(Map<String, String> segments) {
        String plan = blankToNull(segments.get("plan"));
        if (plan == null) {
            return Collections.emptyList();
        }

        String enforcement = blankToNull(segments.get("enforcement"));
        String reason = enforcement == null ? plan : plan + "\n" + enforcement;
        return List.of(new PlanStep(1, actionFrom(plan), reason));
    }

    private static String actionFrom(String plan) {
        if (plan.contains("webTool")) {
            return "webTool";
        }
        if (plan.contains("calculatorTool")) {
            return "calculatorTool";
        }
        if (plan.contains("weatherTool")) {
            return "weatherTool";
        }
        if (plan.contains("bashTool")) {
            return "bashTool";
        }
        return "knowledgeSearchTool";
    }

    private static void containsIfPresent(
            EvaluationCase evalCase,
            String actualPrompt,
            String segmentName,
            String segmentValue) {
        String expected = blankToNull(segmentValue);
        if (expected == null) {
            return;
        }
        assertThat(actualPrompt)
                .as("case '%s' should contain non-empty %s segment", evalCase.id(), segmentName)
                .contains(expected);
    }

    private static int maxStepsFrom(String toolSegment) {
        if (toolSegment == null) {
            return DEFAULT_MAX_STEPS;
        }
        Matcher matcher = FIRST_NUMBER.matcher(toolSegment);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : DEFAULT_MAX_STEPS;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nonBlankOrDefault(String value, String fallback) {
        String nonBlank = blankToNull(value);
        return nonBlank == null ? fallback : nonBlank;
    }
}
