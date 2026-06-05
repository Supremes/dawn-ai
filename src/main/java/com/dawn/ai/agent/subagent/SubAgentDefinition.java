package com.dawn.ai.agent.subagent;

import java.util.Set;

/**
 * Sub-agent 类型的配置定义。
 *
 * <p>一种 {@code type} = 一组（systemPrompt + 工具白名单 + 资源边界）。
 * MVP 仅注册 {@code research} 一种，未来加新类型只需新增 {@link org.springframework.context.annotation.Bean}。
 *
 * <p>{@link #modelOverride} 与 {@link #temperatureOverride} 为 {@code null} 时回退到主 Agent 模型。
 */
public record SubAgentDefinition(
        String type,
        String systemPrompt,
        Set<String> allowedTools,
        int maxSteps,
        int timeoutSeconds,
        String modelOverride,
        Double temperatureOverride
) {
    public SubAgentDefinition {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type 不能为空");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt 不能为空");
        }
        if (allowedTools == null || allowedTools.isEmpty()) {
            throw new IllegalArgumentException("allowedTools 不能为空（至少给 sub-agent 一个工具）");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps 必须 > 0");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds 必须 > 0");
        }
        allowedTools = Set.copyOf(allowedTools);
    }
}
