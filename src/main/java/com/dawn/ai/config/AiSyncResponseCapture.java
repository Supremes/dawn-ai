package com.dawn.ai.config;

/**
 * Captures the last synchronous AI response body on the current thread.
 *
 * <p>Used as a fallback for providers/SDK paths where Spring AI does not
 * surface reasoning content through ChatResponse metadata.
 */
public final class AiSyncResponseCapture {

    private static final ThreadLocal<String> LAST_RESPONSE_BODY = new ThreadLocal<>();

    private AiSyncResponseCapture() {
    }

    public static void set(String responseBody) {
        LAST_RESPONSE_BODY.set(responseBody);
    }

    public static String get() {
        return LAST_RESPONSE_BODY.get();
    }

    public static void clear() {
        LAST_RESPONSE_BODY.remove();
    }
}