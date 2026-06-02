package com.dawn.ai.memory;

import java.util.List;

public record FactsExtractedEvent(String sessionId, String userId, List<String> facts) {
}
