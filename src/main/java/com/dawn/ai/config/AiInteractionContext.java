package com.dawn.ai.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Carries immutable per-request AI interaction state across servlet, Reactor and
 * sub-agent worker threads.
 */
public final class AiInteractionContext {

    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    private AiInteractionContext() {
    }

    public record State(
            String sessionId,
            Set<String> enabledTools,
            Set<String> enabledSkills
    ) {
        public State {
            enabledTools = immutableOrNull(enabledTools);
            enabledSkills = immutableOrNull(enabledSkills);
        }

        private static Set<String> immutableOrNull(Set<String> values) {
            if (values == null) {
                return null;
            }
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value);
                }
            }
            return Collections.unmodifiableSet(normalized);
        }
    }

    public static void set(String sessionId, Set<String> enabledTools, Set<String> enabledSkills) {
        if (sessionId == null || sessionId.isBlank()) {
            clear();
            return;
        }
        STATE.set(new State(sessionId, enabledTools, enabledSkills));
    }

    public static void setSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            clear();
            return;
        }
        State current = STATE.get();
        STATE.set(new State(
                sessionId,
                current != null ? current.enabledTools() : null,
                current != null ? current.enabledSkills() : null));
    }

    public static String getSessionId() {
        State current = STATE.get();
        return current != null ? current.sessionId() : null;
    }

    public static Set<String> getEnabledTools() {
        State current = STATE.get();
        return current != null ? current.enabledTools() : null;
    }

    public static Set<String> getEnabledSkills() {
        State current = STATE.get();
        return current != null ? current.enabledSkills() : null;
    }

    public static boolean isToolEnabled(String toolName) {
        Set<String> enabled = getEnabledTools();
        return enabled == null || enabled.contains(toolName);
    }

    public static boolean isSkillEnabled(String skillName) {
        Set<String> enabled = getEnabledSkills();
        return enabled == null || enabled.contains(skillName);
    }

    public static void clear() {
        STATE.remove();
    }

    static State snapshot() {
        return STATE.get();
    }

    static void restore(State state) {
        if (state == null) {
            STATE.remove();
        } else {
            STATE.set(state);
        }
    }

    /**
     * Wraps a task so the captured interaction snapshot is restored on the worker
     * thread and the previous worker state is restored afterwards.
     */
    public static Runnable wrap(Runnable task) {
        State captured = STATE.get();
        return () -> {
            State previous = STATE.get();
            restore(captured);
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        State captured = STATE.get();
        return () -> {
            State previous = STATE.get();
            restore(captured);
            try {
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }
}
