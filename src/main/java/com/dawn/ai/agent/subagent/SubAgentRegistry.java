package com.dawn.ai.agent.subagent;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sub-agent 类型注册中心。
 *
 * <p>自动收集容器内所有 {@link SubAgentDefinition} bean，按 {@code type} 建立索引，
 * 心智上与 {@link com.dawn.ai.agent.registry.ToolRegistry} 对称。
 *
 * <p>commit 1 阶段无任何 Definition bean 注册，registry 为空。
 * Definition bean 在 commit 2 由 {@code SubAgentConfig} 注入。
 */
@Slf4j
@Component
public class SubAgentRegistry {

    private final List<SubAgentDefinition> definitions;
    private Map<String, SubAgentDefinition> byType = Collections.emptyMap();

    public SubAgentRegistry(List<SubAgentDefinition> definitions) {
        this.definitions = definitions;
    }

    @PostConstruct
    void init() {
        Map<String, SubAgentDefinition> next = new LinkedHashMap<>();
        for (SubAgentDefinition def : definitions) {
            SubAgentDefinition existing = next.put(def.type(), def);
            if (existing != null) {
                log.warn("[SubAgentRegistry] 同名 sub-agent type '{}' 被覆盖", def.type());
            }
        }
        this.byType = Collections.unmodifiableMap(next);
        log.info("[SubAgentRegistry] init complete: {} sub-agent type(s) registered: {}",
                byType.size(), byType.keySet());
    }

    public Optional<SubAgentDefinition> get(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    public Collection<SubAgentDefinition> list() {
        return byType.values();
    }

    public boolean isEmpty() {
        return byType.isEmpty();
    }
}
