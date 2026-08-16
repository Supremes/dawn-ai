package com.dawn.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Set;

@Data
public class ChatRequest {

    @NotBlank(message = "Message cannot be blank")
    private String message;

    /** Conversation session ID for memory context */
    private String sessionId;

    /** Optional research topic context — restricts KnowledgeSearchTool to this topic */
    private String topicId;

    /**
     * Request-scoped tool allowlist. {@code null} keeps the server defaults;
     * an empty set explicitly disables all user-selectable tools.
     */
    private Set<String> enabledTools;

    /**
     * Request-scoped skill allowlist. {@code null} keeps the server defaults;
     * an empty set explicitly disables all skills.
     */
    private Set<String> enabledSkills;
}
