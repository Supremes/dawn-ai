package com.dawn.ai.agent.tools;

import com.dawn.ai.agent.skill.SkillRegistry;
import com.dawn.ai.agent.skill.SkillResourceException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * 元工具：读取已注册 skill 的内嵌资源文件（progressive disclosure 第二层）。
 *
 * <p>典型用法：模型调用 {@code load_skill} 拿到 {@code availableResources} 后，
 * 决定要进一步深入哪个子文件，再调用本工具。
 *
 * <p>安全：路径穿越防御与 source-aware 读取由 {@link SkillRegistry#readResource}
 * 集中实现；本工具仅做参数校验 + 错误包装，避免抛异常打断 agent loop。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Description("读取已加载 skill 的内嵌资源文件（如 references/ 下的细节文档）。仅在 load_skill 返回的 availableResources 列表中出现的路径才可读取。Input: skill (skill 名), path (相对路径如 references/checklist.md)")
public class ReadSkillResourceTool implements Function<ReadSkillResourceTool.Request, ReadSkillResourceTool.Response> {

    private final SkillRegistry skillRegistry;

    public record Request(
            @JsonProperty(required = true)
            @JsonPropertyDescription("Skill 名称，必须与之前 load_skill 调用的 name 一致")
            String skill,

            @JsonProperty(required = true)
            @JsonPropertyDescription("相对 skill 资源根的子路径，例如 references/checklist.md。禁止以 / 开头或包含 ..")
            String path
    ) {}

    public record Response(
            String skill,
            String path,
            String content,
            String error
    ) {
        public static Response success(String skill, String path, String content) {
            return new Response(skill, path, content, null);
        }

        public static Response error(String skill, String path, String message) {
            return new Response(skill, path, null, message);
        }
    }

    @Override
    public Response apply(Request request) {
        if (request == null) {
            return Response.error(null, null, "缺少请求参数");
        }
        if (request.skill() == null || request.skill().isBlank()) {
            return Response.error(null, request.path(), "缺少必填参数 skill");
        }
        if (request.path() == null || request.path().isBlank()) {
            return Response.error(request.skill(), null, "缺少必填参数 path");
        }
        try {
            String content = skillRegistry.readResource(request.skill(), request.path());
            log.info("[ReadSkillResourceTool] read: skill={}, path={}, chars={}",
                    request.skill(), request.path(), content.length());
            return Response.success(request.skill(), request.path(), content);
        } catch (SkillResourceException e) {
            log.warn("[ReadSkillResourceTool] failed: skill={}, path={}, err={}",
                    request.skill(), request.path(), e.getMessage());
            return Response.error(request.skill(), request.path(), e.getMessage());
        }
    }
}
