package com.dawn.ai.agent.skill;

import java.nio.file.Path;

/**
 * 内存中的 Skill 表示：frontmatter + SKILL.md 正文 + 资源根目录。
 *
 * <p>{@link #resourceDir} 语义：
 * <ul>
 *   <li>{@link Source#EXTERNAL}：{@code SKILL.md} 所在目录的绝对路径，
 *       供 {@link SkillRegistry#readResource} 做 startsWith 校验 + filesystem 读取。</li>
 *   <li>{@link Source#BUILTIN}：{@code null}。jar 内 classpath 资源没有统一的
 *       {@link Path} 抽象，资源读取走 {@code ClassPathResource} 路径，资源根隐含
 *       为 {@code classpath:skills-builtin/{name}/}。</li>
 * </ul>
 */
public record Skill(
        SkillManifest manifest,
        String body,
        Path resourceDir,
        Source source
) {
    public enum Source {
        /** 来自 classpath {@code skills-builtin/} 的兜底示例 */
        BUILTIN,
        /** 来自外挂目录 {@code app.skills.path} 的用户自定义 skill */
        EXTERNAL
    }
}
