package com.dawn.ai.agent.skill;

import java.util.Map;

/**
 * SKILL.md frontmatter 的强类型表示。
 *
 * <p>v1 仅消费 {@code name} 与 {@code description}；其他字段（如 Anthropic 原版的
 * {@code version} / {@code allowed-tools} / {@code license}）一并解析进 {@link #extras}
 * 保留，但本版本不消费——保证未来扩展时无需改解析层。
 */
public record SkillManifest(
        String name,
        String description,
        Map<String, Object> extras
) {}
