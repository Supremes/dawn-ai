package com.dawn.ai.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatResponse {
    private String sessionId;
    private String answer;
    private long durationMs;
    private String model;
}
