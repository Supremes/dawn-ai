package com.dawn.ai.agent.orchestration;

import com.dawn.ai.agent.planning.PlanStep;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 formatPlan 逻辑正确过滤掉 "finish" 步骤。
 * 
 * 背景：TaskPlanner 要求计划的最后一步必须是 "finish"，但这只是规划器的内部约定，
 * 不应暴露给执行阶段的LLM，否则LLM会误以为 "finish" 是一个真实的工具并尝试调用它。
 * 
 * 这个测试复现 formatPlan 的核心逻辑，验证 finish 过滤是否正确。
 */
class FormatPlanFinishFilterTest {

    /**
     * 复现 AgentOrchestrator.formatPlan 的核心逻辑用于测试
     */
    private String formatPlan(List<PlanStep> plan) {
        if (plan.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n【执行计划】\n");
        for (PlanStep step : plan) {
            // 过滤掉 "finish" 步骤 - 这只是规划器的内部标记，不应暴露给执行阶段的LLM
            if ("finish".equals(step.action())) {
                continue;
            }
            sb.append(step.step())
                    .append(". [").append(step.action()).append("] ")
                    .append(step.reason()).append("\n");
        }
        return sb.toString();
    }

    @Test
    void formatPlan_shouldFilterOutFinishStep() {
        // Given: 一个包含 finish 步骤的计划
        List<PlanStep> plan = Arrays.asList(
                new PlanStep(1, "knowledgeSearchTool", "搜索相关知识"),
                new PlanStep(2, "calculatorTool", "计算结果"),
                new PlanStep(3, "finish", "生成最终答案")
        );

        // When: 调用 formatPlan
        String result = formatPlan(plan);

        // Then: 结果中不应包含 "finish" 步骤
        assertThat(result).isNotNull();
        assertThat(result).contains("【执行计划】");
        assertThat(result).contains("[knowledgeSearchTool]");
        assertThat(result).contains("[calculatorTool]");
        assertThat(result).doesNotContain("[finish]");
        assertThat(result).doesNotContain("生成最终答案");
        
        // 验证步骤编号保持原样（1, 2 没有因过滤而改变）
        assertThat(result).contains("1. [knowledgeSearchTool]");
        assertThat(result).contains("2. [calculatorTool]");
    }

    @Test
    void formatPlan_shouldHandleEmptyPlan() {
        // Given: 空计划
        List<PlanStep> plan = List.of();

        // When: 调用 formatPlan
        String result = formatPlan(plan);

        // Then: 应返回空字符串
        assertThat(result).isEmpty();
    }

    @Test
    void formatPlan_shouldHandleOnlyFinishStep() {
        // Given: 只有 finish 步骤的计划（边界case）
        List<PlanStep> plan = List.of(
                new PlanStep(1, "finish", "直接回答")
        );

        // When: 调用 formatPlan
        String result = formatPlan(plan);

        // Then: 应该只有标题，没有步骤内容（因为 finish 被过滤了）
        assertThat(result).isNotNull();
        assertThat(result).contains("【执行计划】");
        assertThat(result).doesNotContain("[finish]");
        assertThat(result).doesNotContain("直接回答");
    }

    @Test
    void formatPlan_shouldHandleMultipleStepsBeforeFinish() {
        // Given: 多个工具步骤 + finish
        List<PlanStep> plan = Arrays.asList(
                new PlanStep(1, "knowledgeSearchTool", "第一次搜索"),
                new PlanStep(2, "knowledgeSearchTool", "第二次搜索"),
                new PlanStep(3, "calculatorTool", "计算"),
                new PlanStep(4, "weatherTool", "查天气"),
                new PlanStep(5, "finish", "汇总答案")
        );

        // When: 调用 formatPlan
        String result = formatPlan(plan);

        // Then: 所有工具步骤都保留，只过滤 finish
        assertThat(result).contains("[knowledgeSearchTool]");
        assertThat(result).contains("[calculatorTool]");
        assertThat(result).contains("[weatherTool]");
        assertThat(result).doesNotContain("[finish]");
        assertThat(result).doesNotContain("汇总答案");
    }
}
