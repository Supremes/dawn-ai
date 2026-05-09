package com.dawn.ai.config;

import java.util.concurrent.Callable;

/**
 * Carries the current chat sessionId on the executing thread so low-level HTTP
 * interceptors can attribute Spring AI request/response bodies to a session.
 */
public final class AiInteractionContext {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    private AiInteractionContext() {
    }

    public static void setSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            SESSION_ID.remove();
            return;
        }
        SESSION_ID.set(sessionId);
    }

    public static String getSessionId() {
        return SESSION_ID.get();
    }

    public static void clear() {
        SESSION_ID.remove();
    }

    /**
     * Wraps a Runnable so the captured sessionId is restored on the worker thread
     * before {@code task} runs, then cleared afterwards. Use when submitting work
     * that needs to attribute downstream LLM/embedding calls back to the originating
     * chat session.
     */
    public static Runnable wrap(Runnable task) {
        String captured = SESSION_ID.get();
        if (captured == null) return task;
        return () -> {
            String prev = SESSION_ID.get();
            SESSION_ID.set(captured);
            try {
                task.run();
            } finally {
                if (prev == null) SESSION_ID.remove();
                else SESSION_ID.set(prev);
            }
        };
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        String captured = SESSION_ID.get();
        if (captured == null) return task;
        return () -> {
            String prev = SESSION_ID.get();
            SESSION_ID.set(captured);
            try {
                return task.call();
            } finally {
                if (prev == null) SESSION_ID.remove();
                else SESSION_ID.set(prev);
            }
        };
    }
}
