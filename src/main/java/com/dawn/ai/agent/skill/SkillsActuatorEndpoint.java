package com.dawn.ai.agent.skill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Actuator endpoint：
 * <ul>
 *   <li>{@code GET  /actuator/skills} — 列出当前注册的 skill（name/description/source）</li>
 *   <li>{@code POST /actuator/skills} — 触发 {@link SkillRegistry#refresh()}，返回 added/removed/failed 差异</li>
 * </ul>
 *
 * <p>注意：reload 只刷新 Registry；老 session 持有的 system prompt 已固化，新 session
 * 才能见到最新 skill 清单（这是 grill 阶段已确认的预期行为）。
 */
@Slf4j
@Component
@Endpoint(id = "skills")
@RequiredArgsConstructor
public class SkillsActuatorEndpoint {

    private final SkillRegistry skillRegistry;

    @ReadOperation
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", skillRegistry.isEnabled());
        List<Map<String, Object>> entries = skillRegistry.list().stream()
                .map(s -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("name", s.manifest().name());
                    e.put("description", s.manifest().description());
                    e.put("source", s.source().name());
                    return e;
                })
                .toList();
        result.put("count", entries.size());
        result.put("skills", entries);
        return result;
    }

    @WriteOperation
    public Map<String, Object> reload() {
        log.info("[SkillsActuatorEndpoint] reload requested");
        SkillRegistry.RefreshResult r = skillRegistry.refresh();
        log.info("[SkillsActuatorEndpoint] reload done: total={}, added={}, removed={}, failed={}",
                r.total(), r.added().size(), r.removed().size(), r.failed().size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", r.total());
        result.put("builtinCount", r.builtinCount());
        result.put("externalCount", r.externalCount());
        result.put("added", r.added());
        result.put("removed", r.removed());
        result.put("failed", r.failed());
        return result;
    }
}
