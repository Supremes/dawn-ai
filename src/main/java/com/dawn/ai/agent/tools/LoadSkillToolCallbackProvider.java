package com.dawn.ai.agent.tools;

import com.dawn.ai.agent.skill.Skill;
import com.dawn.ai.agent.skill.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.Collection;

/** Provides a dynamic enum schema for loadSkillTool.name. */
@Component
@RequiredArgsConstructor
public class LoadSkillToolCallbackProvider implements ToolCallbackProvider {

    private static final String TOOL_NAME = "loadSkillTool";
    private static final String DESCRIPTION = "加载指定 Skill 的完整指令内容。当 system prompt 的【可用 Skills】清单中某个 skill 的描述匹配当前任务时调用。Input: name (skill 名)";
    private static final String NO_AVAILABLE_SKILL = "__NO_AVAILABLE_SKILL__";

    private final LoadSkillTool loadSkillTool;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public ToolCallback[] getToolCallbacks() {
        return new ToolCallback[] { new DynamicSchemaLoadSkillToolCallback(loadSkillTool, skillRegistry, objectMapper) };
    }

    private static final class DynamicSchemaLoadSkillToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final SkillRegistry skillRegistry;
        private final ObjectMapper objectMapper;

        private DynamicSchemaLoadSkillToolCallback(LoadSkillTool loadSkillTool,
                                                   SkillRegistry skillRegistry,
                                                   ObjectMapper objectMapper) {
            this.delegate = FunctionToolCallback
                    .<LoadSkillTool.Request, LoadSkillTool.Response>builder(TOOL_NAME, loadSkillTool)
                    .description(DESCRIPTION)
                    .inputType(LoadSkillTool.Request.class)
                    .build();
            this.skillRegistry = skillRegistry;
            this.objectMapper = objectMapper;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(TOOL_NAME)
                    .description(DESCRIPTION)
                    .inputSchema(buildInputSchema(skillRegistry.list()))
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return delegate.call(toolInput, toolContext);
        }

        private String buildInputSchema(Collection<Skill> skills) {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("type", "object");

            ObjectNode properties = root.putObject("properties");
            ObjectNode name = properties.putObject("name");
            name.put("type", "string");
            name.put("description", "Skill 名称，必须从 enum 中选择；没有明确匹配时不要调用本工具。");

            ArrayNode enumValues = name.putArray("enum");
            if (skills.isEmpty()) {
                enumValues.add(NO_AVAILABLE_SKILL);
            } else {
                skills.stream()
                        .map(skill -> skill.manifest().name())
                        .sorted()
                        .forEach(enumValues::add);
            }

            root.putArray("required").add("name");
            root.put("additionalProperties", false);
            return root.toString();
        }
    }
}