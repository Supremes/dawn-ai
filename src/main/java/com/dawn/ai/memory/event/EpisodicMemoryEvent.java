package com.dawn.ai.memory.event;

public record EpisodicMemoryEvent(String sessionId, String userId, String summary, double importance) {
}
