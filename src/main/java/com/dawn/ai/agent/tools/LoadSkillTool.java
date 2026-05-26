package com.dawn.ai.agent.tools;

import com.dawn.ai.agent.skill.Skill;
import com.dawn.ai.agent.skill.SkillRegistry;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 元工具：按需加载某个 Skill 的完整 SKILL.md 内容（progressive disclosure 第一层）。
 *
 * <p>模型在 system prompt 的"## 可用 Skills"清单中看到 skill 的 name+description 后，
 * 判断需要详细指令时调用本工具。返回的 {@code content} 是 SKILL.md 正文（不含 frontmatter），
 * {@code availableResources} 列出该 skill 目录下可继续 {@code read_skill_resource}
 * 加载的子文件路径（progressive disclosure 第二层入口）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Description("加载指定 Skill 的完整指令内容。当 system prompt 的【可用 Skills】清单中某个 skill 的描述匹配当前任务时调用。Input: name (skill 名)")
public class LoadSkillTool implements Function<LoadSkillTool.Request, LoadSkillTool.Response> {

    private final SkillRegistry skillRegistry;

    public record Request(
            @JsonProperty(required = true)
            @JsonPropertyDescription("Skill 名称，必须与 system prompt 中【可用 Skills】列出的 name 完全一致")
            String name
    ) {}

    public record Response(
            String name,
            String description,
            String content,
            List<String> availableResources,
            String error
    ) {
        public static Response success(Skill skill, List<String> resources) {
            return new Response(
                    skill.manifest().name(),
                    skill.manifest().description(),
                    skill.body(),
                    resources,
                    null
            );
        }

        public static Response notFound(String name) {
            return new Response(name, null, null, List.of(),
                    "skill 不存在: " + name + "（请仅从【可用 Skills】清单中选择 name）");
        }
    }

    @Override
    public Response apply(Request request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            log.warn("[LoadSkillTool] 缺少 name 参数");
            return new Response(null, null, null, List.of(), "缺少必填参数 name");
        }
        Optional<Skill> opt = skillRegistry.get(request.name());
        if (opt.isEmpty()) {
            log.info("[LoadSkillTool] skill 不存在: {}", request.name());
            return Response.notFound(request.name());
        }
        Skill skill = opt.get();
        List<String> resources = skillRegistry.listResources(skill.manifest().name());
        log.info("[LoadSkillTool] loaded skill='{}', source={}, bodyChars={}, resources={}",
                skill.manifest().name(), skill.source(), skill.body().length(), resources.size());
        return Response.success(skill, resources);
    }
}
