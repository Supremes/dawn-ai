package com.dawn.ai.agent.token;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 感知 token 的上下文窗口管理器。
 *
 * <p>封装 Spring AI 的 {@link JTokkitTokenCountEstimator}，提供 token 计数，
 * 并按预算截断工具输出、对话历史和 system prompt 各段内容。
 * 所有预算阈值都通过 {@code app.ai.token.*} 配置项外置。
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
     * 估算给定文本的 token 数。
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return tokenCountEstimator.estimate(text);
    }

    /**
     * 把文本截断到 token 预算以内。
     *
     * <p>先根据实际文本算出每个 token 平均多少字符，以此找一个安全的字符级切点，
     * 再对齐到最后一个换行处，让输出更干净。末尾会追加一句提示，说明内容被截断了。
     */
    public String truncateToTokenBudget(String text, int maxTokens) {
        if (text == null || text.isEmpty()) return text;
        int tokens = estimateTokens(text);
        if (tokens <= maxTokens) return text;

        // 估算这段文本的字符/token 比例，留出安全余量后截断
        double ratio = (double) text.length() / tokens;
        int targetChars = (int) (maxTokens * ratio * 0.9);
        String truncated = text.substring(0, Math.min(targetChars, text.length()));
        // 尽量在最后一个换行处切，截断得更干净
        int lastNewline = truncated.lastIndexOf('\n');
        if (lastNewline > targetChars / 2) {
            truncated = truncated.substring(0, lastNewline);
        }
        log.debug("[TokenWindowManager] Truncated text from {} to ~{} tokens (budget {})",
                tokens, estimateTokens(truncated), maxTokens);
        return truncated + "\n...[已截断，原文约 " + tokens + " tokens]";
    }

    /**
     * 按配置的工具输出 token 预算截断工具输出。
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
