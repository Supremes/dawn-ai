package com.dawn.ai.agent.tools;

import com.dawn.ai.agent.skill.Skill;
import com.dawn.ai.agent.skill.SkillManifest;
import com.dawn.ai.agent.skill.SkillRegistry;
import com.dawn.ai.agent.tools.skill.LoadSkillTool;
import com.dawn.ai.agent.tools.skill.LoadSkillToolCallbackProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoadSkillToolCallbackProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesAvailableSkillNamesAsEnum() throws Exception {
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        when(skillRegistry.list()).thenReturn(List.of(
                skill("grill-me", "Grill plans"),
                skill("code-review-zh", "Review Java code")
        ));

        LoadSkillToolCallbackProvider provider = new LoadSkillToolCallbackProvider(
                mock(LoadSkillTool.class), skillRegistry, objectMapper);

        JsonNode schema = objectMapper.readTree(provider.getToolCallbacks()[0].getToolDefinition().inputSchema());

        assertThat(schema.path("properties").path("name").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactly("code-review-zh", "grill-me");
        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactly("name");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void usesSentinelEnumWhenNoSkillsAreAvailable() throws Exception {
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        when(skillRegistry.list()).thenReturn(List.of());

        LoadSkillToolCallbackProvider provider = new LoadSkillToolCallbackProvider(
                mock(LoadSkillTool.class), skillRegistry, objectMapper);

        JsonNode schema = objectMapper.readTree(provider.getToolCallbacks()[0].getToolDefinition().inputSchema());

        assertThat(schema.path("properties").path("name").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactly("__NO_AVAILABLE_SKILL__");
    }

    private Skill skill(String name, String description) {
        return new Skill(new SkillManifest(name, description, Map.of()), "body", null, Skill.Source.BUILTIN);
    }
}