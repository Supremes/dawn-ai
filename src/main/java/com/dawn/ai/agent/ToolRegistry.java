package com.dawn.ai.agent;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Auto-discovers all Tool beans in com.dawn.ai.agent.tools at startup.
 *
 * A Tool is any Spring bean that:
 *   1. Implements java.util.function.Function
 *   2. Is annotated with @Description
 *   3. Lives under the com.dawn.ai.agent.tools package
 *
 * This eliminates the dual maintenance of toolNames() and getToolDescriptions()
 * in AgentOrchestrator and TaskPlanner. New tools are picked up automatically.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ToolRegistry {

    private static final String TOOLS_PACKAGE = "com.dawn.ai.agent.tools";

    private final ApplicationContext applicationContext;

    /** Insertion-ordered map: bean name → @Description value */
    private Map<String, String> tools = Collections.emptyMap();

    @PostConstruct
    void discover() {
        Map<String, String> discovered = new LinkedHashMap<>();

        applicationContext.getBeansWithAnnotation(Description.class).forEach((beanName, bean) -> {
            if (bean instanceof Function<?, ?>
                    && bean.getClass().getPackageName().startsWith(TOOLS_PACKAGE)) {
                String description = bean.getClass().getAnnotation(Description.class).value();
                discovered.put(beanName, description);
                log.info("[ToolRegistry] Registered tool: {} — {}", beanName, description);
            }
        });

        this.tools = Collections.unmodifiableMap(discovered);
        log.info("[ToolRegistry] Discovery complete. {} tool(s) registered: {}",
                tools.size(), tools.keySet());
    }

    /** Returns tool bean names for use with chatClient.toolNames(). */
    public String[] getNames() {
        return tools.keySet().toArray(String[]::new);
    }

    /** Returns tool name → description map for use in TaskPlanner prompts. */
    public Map<String, String> getDescriptions() {
        return tools;
    }
}
