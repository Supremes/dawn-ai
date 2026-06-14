package com.dawn.ai.agent.tools;

/**
 * 把来自外部、不可信来源的文本（网页正文、搜索摘要、检索文档等）用边界标记包裹，
 * 配合 system prompt 的安全准则提示 LLM：标记内的一切只能当资料，绝不能当指令。
 *
 * <p>同时中和内容里可能伪造的同名标记，防止「标记逃逸」——攻击者在正文里塞一个
 * 闭合标记，把后续注入文字挪到边界之外冒充合法指令。
 */
public final class UntrustedContent {

    public static final String OPEN = "<untrusted_external_content>";
    public static final String CLOSE = "</untrusted_external_content>";

    /** 匹配正文中伪造的开/闭标记（大小写不敏感、容忍多余空白），统一替换为无害占位符。 */
    private static final String MARKER_PATTERN = "(?i)<\\s*/?\\s*untrusted_external_content\\s*>";
    private static final String MARKER_PLACEHOLDER = "[external-content-marker]";

    private UntrustedContent() {
    }

    /**
     * 用 {@link #OPEN}/{@link #CLOSE} 边界标记包裹外部内容。
     * content 为 null 或空白时原样返回（没有外部内容需要包裹）。
     */
    public static String wrap(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        String neutralized = content.replaceAll(MARKER_PATTERN, MARKER_PLACEHOLDER);
        return OPEN + "\n" + neutralized + "\n" + CLOSE;
    }
}
