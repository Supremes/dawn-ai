package com.dawn.ai.memory;

public record EpisodicMemoryEvent(String sessionId, String userId, String summary, double importance) {
}
