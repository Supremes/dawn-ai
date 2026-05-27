package com.dawn.ai.agent.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析单个 {@code SKILL.md} 文件 → {@link Skill}。
 *
 * <p>文件格式（与 Anthropic 原版一致）：
 * <pre>
 * ---
 * name: pdf-analysis
 * description: 简短描述，必填
 * version: 1.2.0          # 可选，保留进 extras 不消费
 * ---
 *
 * SKILL.md 正文……
 * </pre>
 *
 * <p>校验规则（违反即抛 {@link SkillLoadException}，由调用方跳过该 skill）：
 * <ul>
 *   <li>必须含完整 frontmatter（{@code ---} ... {@code ---}）</li>
 *   <li>{@code name} 必填，必须匹配 kebab-case 正则 {@code ^[a-z][a-z0-9-]{0,63}$}</li>
 *   <li>{@code description} 必填；超 1024 字符截断 + WARN，不视为错误</li>
 * </ul>
 */
@Slf4j
@Component
public class SkillLoader {

    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\s*\\R(.*?)\\R---\\s*\\R?(.*)$", Pattern.DOTALL);

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    private static final int DESCRIPTION_MAX_CHARS = 1024;

    public Skill loadFromFile(Path skillMdPath, Path resourceDir, Skill.Source source) {
        String content;
        try {
            content = Files.readString(skillMdPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SkillLoadException("无法读取 SKILL.md: " + skillMdPath, e);
        }
        return parse(content, resourceDir, source, skillMdPath.toString());
    }

    public Skill loadFromClasspath(InputStream in, Path resourceDir, String origin) {
        String content;
        try {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SkillLoadException("无法读取 classpath SKILL.md: " + origin, e);
        }
        return parse(content, resourceDir, Skill.Source.BUILTIN, origin);
    }

    private Skill parse(String content, Path resourceDir, Skill.Source source, String origin) {
        Matcher matcher = FRONTMATTER.matcher(content);
        if (!matcher.matches()) {
            throw new SkillLoadException("SKILL.md 缺少 frontmatter (--- ... ---): " + origin);
        }

        String yamlText = matcher.group(1);
        String body = matcher.group(2);

        Map<String, Object> raw = parseYaml(yamlText, origin);

        String name = asString(raw.get("name"));
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new SkillLoadException(
                    "name 非法（要求 kebab-case，正则 ^[a-z][a-z0-9-]{0,63}$）: '" + name + "' @ " + origin);
        }

        String description = asString(raw.get("description"));
        if (description == null || description.isBlank()) {
            throw new SkillLoadException("description 必填: " + origin);
        }
        if (description.length() > DESCRIPTION_MAX_CHARS) {
            log.warn("[SkillLoader] description 超 {} 字符（实际 {}）已截断: name={}",
                    DESCRIPTION_MAX_CHARS, description.length(), name);
            description = description.substring(0, DESCRIPTION_MAX_CHARS);
        }

        Map<String, Object> extras = new LinkedHashMap<>(raw);
        extras.remove("name");
        extras.remove("description");

        SkillManifest manifest = new SkillManifest(name, description, Map.copyOf(extras));
        return new Skill(manifest, body, resourceDir, source);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYaml(String yamlText, String origin) {
        try {
            Object parsed = new Yaml().load(yamlText);
            if (parsed == null) {
                return Map.of();
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new SkillLoadException("frontmatter 必须是 YAML map: " + origin);
            }
            return (Map<String, Object>) map;
        } catch (SkillLoadException e) {
            throw e;
        } catch (Exception e) {
            throw new SkillLoadException("frontmatter YAML 解析失败: " + origin, e);
        }
    }

    private String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
