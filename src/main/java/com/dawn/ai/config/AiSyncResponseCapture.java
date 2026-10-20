package com.dawn.ai.config;

/**
 * 在当前线程上记录最近一次同步 AI 响应的 body。
 *
 * <p>作为 fallback 使用：有些 provider / SDK 路径下，Spring AI 不会把
 * reasoning 内容放进 ChatResponse metadata，这时就从这里取。
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