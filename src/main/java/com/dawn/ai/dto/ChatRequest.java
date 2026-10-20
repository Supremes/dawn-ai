package com.dawn.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Set;

@Data
public class ChatRequest {

    @NotBlank(message = "Message cannot be blank")
    private String message;

    /** 会话 session ID，用来带上 memory 上下文 */
    private String sessionId;

    /** 可选的研究主题上下文，把 KnowledgeSearchTool 限制在这个主题内 */
    private String topicId;

    /**
     * 请求级别的工具白名单。{@code null} 表示沿用服务端默认值；
     * 空集合表示明确关掉所有用户可选的工具。
     */
    private Set<String> enabledTools;

    /**
     * 请求级别的 skill 白名单。{@code null} 表示沿用服务端默认值；
     * 空集合表示明确关掉所有 skill。
     */
    private Set<String> enabledSkills;
}
