package com.dawn.ai.memory.event;

import java.util.List;
import java.util.Map;

public record SummarizationRequestEvent(String sessionId, String userId, List<Map<String, String>> messages) {}
