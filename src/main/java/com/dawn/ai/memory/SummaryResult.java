package com.dawn.ai.memory;

import java.time.Instant;

public record SummaryResult(String sessionId, String text, double importanceScore, Instant createdAt) {}
