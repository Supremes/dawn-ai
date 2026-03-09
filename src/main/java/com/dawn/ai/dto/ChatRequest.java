package com.dawn.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {

    @NotBlank(message = "Message cannot be blank")
    private String message;

    /** Conversation session ID for memory context */
    private String sessionId;

    /** Whether to enable RAG retrieval */
    private boolean ragEnabled = false;
}
