package com.dawn.ai.agent.token;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Token-aware context window manager.
 *
 * <p>Wraps Spring AI's {@link JTokkitTokenCountEstimator} to provide token counting
 * and budget-based truncation for tool output, conversation history, and system prompt
 * sections. All budget thresholds are externalized via {@code app.ai.token.*} properties.
 */
@Slf4j
@Component
public class TokenWindowManager {

    private final TokenCountEstimator tokenCountEstimator;

    @Value("${app.ai.token.max-tool-output-tokens:3000}")
    private int maxToolOutputTokens;

    @Value("${app.ai.token.max-history-tokens:8000}")
    private int maxHistoryTokens;

    @Value("${app.ai.token.max-memory-tokens:1000}")
    private int maxMemoryTokens;

    @Value("${app.ai.token.max-skills-tokens:500}")
    private int maxSkillsTokens;

    public TokenWindowManager() {
        this.tokenCountEstimator = new JTokkitTokenCountEstimator();
    }

    /**
     * Estimate the number of tokens in the given text.
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return tokenCountEstimator.estimate(text);
    }

    /**
     * Truncate text to fit within a token budget.
     *
     * <p>Uses a chars-per-token ratio heuristic derived from the actual text to find
     * a safe character-level cut point, then snaps to the last newline boundary for
     * cleaner output. A trailing note is appended to signal truncation.
     */
    public String truncateToTokenBudget(String text, int maxTokens) {
        if (text == null || text.isEmpty()) return text;
        int tokens = estimateTokens(text);
        if (tokens <= maxTokens) return text;

        // Estimate chars-per-token ratio for this text, truncate with safety margin
        double ratio = (double) text.length() / tokens;
        int targetChars = (int) (maxTokens * ratio * 0.9);
        String truncated = text.substring(0, Math.min(targetChars, text.length()));
        // Try to cut at last newline for cleaner truncation
        int lastNewline = truncated.lastIndexOf('\n');
        if (lastNewline > targetChars / 2) {
            truncated = truncated.substring(0, lastNewline);
        }
        log.debug("[TokenWindowManager] Truncated text from {} to ~{} tokens (budget {})",
                tokens, estimateTokens(truncated), maxTokens);
        return truncated + "\n...[已截断，原文约 " + tokens + " tokens]";
    }

    /**
     * Truncate tool output to the configured tool-output token budget.
     */
    public String truncateToolOutput(String output) {
        return truncateToTokenBudget(output, maxToolOutputTokens);
    }

    public int getMaxHistoryTokens() {
        return maxHistoryTokens;
    }

    public int getMaxMemoryTokens() {
        return maxMemoryTokens;
    }

    public int getMaxSkillsTokens() {
        return maxSkillsTokens;
    }

    public int getMaxToolOutputTokens() {
        return maxToolOutputTokens;
    }
}
