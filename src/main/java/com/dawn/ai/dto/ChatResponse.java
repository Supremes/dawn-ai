package com.dawn.ai.dto;

import com.dawn.ai.agent.AgentStep;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ChatResponse {
    private String sessionId;
    private String answer;
    private long durationMs;
    private String model;
    private List<AgentStep> steps;    // tool call details; null when show-steps=false
    private String planSummary;       // e.g. "步骤1: weatherTool → 步骤2: 完成"
    private int totalSteps;           // number of tool calls actually made
}
