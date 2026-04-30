package com.dawn.ai.memory;

import java.util.List;
import java.util.Map;

public record SummarizationRequestEvent(String sessionId, List<Map<String, String>> messages) {}
