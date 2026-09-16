package com.dawn.ai.agent.registry;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Description;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 启动时自动发现 com.dawn.ai.agent.tools 下的所有 Tool bean。
 *
 * 满足下面三个条件的 Spring bean 就算一个 Tool：
 *   1. 实现了 java.util.function.Function
 *   2. 标了 @Description 注解
 *   3. 位于 com.dawn.ai.agent.tools 包下
 *
 * 这样就不用在 AgentOrchestrator 和 TaskPlanner 里同时维护 toolNames() 和 getToolDescriptions() 了，
 * 新增工具会被自动捡起来。
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ToolRegistry {

    private static final String TOOLS_PACKAGE = "com.dawn.ai.agent.tools";
    public static final Set<String> INTERNAL_TOOL_NAMES =
            Set.of("loadSkillTool", "readSkillResourceTool");

    private final ApplicationContext applicationContext;

    /** 按插入顺序排列的 map：bean 名 → @Description 的值 */
    private Map<String, String> tools = Collections.emptyMap();

    @PostConstruct
    void discover() {
        Map<String, String> discovered = new LinkedHashMap<>();

        applicationContext.getBeansWithAnnotation(Description.class).forEach((beanName, bean) -> {
            // 1. 安全地拆掉代理，拿到真正的目标类
            Class<?> targetClass = AopUtils.getTargetClass(bean);

            if (bean instanceof Function<?, ?>
                    && targetClass.getPackageName().startsWith(TOOLS_PACKAGE)) {
                Description descriptionAnnotation = AnnotationUtils.findAnnotation(targetClass, Description.class);
                if (descriptionAnnotation == null) {
                    log.warn("[ToolRegistry] Skipping tool '{}' ({}): missing @Description on target class",
                            beanName, targetClass.getName());
                    return;
                }
                discovered.put(beanName, descriptionAnnotation.value());
                log.info("[ToolRegistry] Registered tool: {} — {}", beanName, descriptionAnnotation.value());
            }
        });

        this.tools = Collections.unmodifiableMap(discovered);
        log.info("[ToolRegistry] Discovery complete. {} tool(s) registered: {}",
                tools.size(), tools.keySet());
    }

    /** 返回工具 bean 名列表，给 chatClient.toolNames() 用。 */
    public String[] getNames() {
        return tools.keySet().toArray(String[]::new);
    }

    /** 返回工具名 → 描述的 map，给 TaskPlanner 的 prompt 用。 */
    public Map<String, String> getDescriptions() {
        return tools;
    }
}
