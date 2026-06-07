package com.dawn.ai.agent.skill;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Skill 发现与注册中心。
 *
 * <p>扫描两条路径（builtin 先扫，external 后扫，同名 external 覆盖 builtin）：
 * <ul>
 *   <li>classpath: {@code skills-builtin/{name}/SKILL.md} —— 打包进 jar 的兜底示例</li>
 *   <li>filesystem: {@code ${app.skills.path}/{name}/SKILL.md} —— 运维外挂目录</li>
 * </ul>
 *
 * <p>错误隔离：单个 SKILL.md 解析失败仅 ERROR 跳过该 skill，不阻塞 app 启动。
 *
 * <p>线程安全：内部 {@code skills} 为不可变 Map，{@link #refresh()} 整体替换引用；
 * {@code refresh} 方法本身 synchronized 防止并发 reload 互相覆盖结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillRegistry {

    private static final String BUILTIN_LOCATION_PREFIX = "skills-builtin";
    private static final String BUILTIN_PATTERN = "classpath*:" + BUILTIN_LOCATION_PREFIX + "/*/SKILL.md";

    private final SkillProperties properties;
    private final SkillLoader loader;
    private final ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    private volatile Map<String, Skill> skills = Map.of();

    @PostConstruct
    void init() {
        if (!properties.isEnabled()) {
            log.info("[SkillRegistry] disabled (app.skills.enabled=false)");
            return;
        }
        RefreshResult result = doRefresh();
        log.info("[SkillRegistry] init complete: total={} (builtin={}, external={}), failed={}",
                result.total(), result.builtinCount(), result.externalCount(), result.failed().size());
    }

    public synchronized RefreshResult refresh() {
        if (!properties.isEnabled()) {
            return new RefreshResult(List.of(), List.of(), List.of(), List.of(), 0, 0);
        }
        return doRefresh();
    }

    public Collection<Skill> list() {
        return skills.values();
    }

    public Optional<Skill> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 读取 skill 内嵌资源（{@code references/}、其他子文件）。
     *
     * <p>路径穿越防御：解析 {@code relativePath} 后强制要求最终路径仍位于该 skill 的资源根下，
     * 否则抛 {@link SkillResourceException}。
     *
     * @param name skill 名
     * @param relativePath 相对 skill 资源根的子路径，如 {@code "references/checklist.md"}
     * @return 资源 UTF-8 文本内容
     */
    public String readResource(String name, String relativePath) {
        Skill skill = skills.get(name);
        if (skill == null) {
            throw new SkillResourceException("skill 不存在: " + name);
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new SkillResourceException("relativePath 不能为空");
        }
        if (relativePath.startsWith("/") || relativePath.contains("\0")) {
            throw new SkillResourceException("非法路径: " + relativePath);
        }
        return switch (skill.source()) {
            case EXTERNAL -> readFromFilesystem(skill, relativePath);
            case BUILTIN -> readFromClasspath(name, relativePath);
        };
    }

    private String readFromFilesystem(Skill skill, String relativePath) {
        Path root;
        Path target;
        try {
            root = skill.resourceDir().toRealPath();
            target = skill.resourceDir().resolve(relativePath).normalize().toRealPath();
        } catch (IOException e) {
            throw new SkillResourceException("资源不存在或不可读: " + relativePath, e);
        }
        if (!target.startsWith(root)) {
            throw new SkillResourceException("拒绝路径穿越: " + relativePath);
        }
        if (!Files.isRegularFile(target)) {
            throw new SkillResourceException("不是常规文件: " + relativePath);
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SkillResourceException("读取失败: " + relativePath, e);
        }
    }

    /**
     * 列出 skill 资源根下所有可被 {@link #readResource} 访问的文件相对路径
        * （排除 {@code SKILL.md} 本身与 {@code scripts/} 子目录）。供 {@code loadSkillTool}
        * 在返回 body 时一并暴露给模型，提示哪些子文件可继续 {@code readSkillResourceTool}。
     */
    public List<String> listResources(String name) {
        Skill skill = skills.get(name);
        if (skill == null) return List.of();
        return switch (skill.source()) {
            case EXTERNAL -> listExternalResources(skill);
            case BUILTIN -> listBuiltinResources(name);
        };
    }

    private List<String> listExternalResources(Skill skill) {
        Path root = skill.resourceDir();
        List<String> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.equals("SKILL.md") || rel.startsWith("scripts/")) return;
                result.add(rel);
            });
        } catch (IOException e) {
            log.warn("[SkillRegistry] listResources(external) 失败: name={}, err={}",
                    skill.manifest().name(), e.getMessage());
        }
        return result;
    }

    private List<String> listBuiltinResources(String skillName) {
        String pattern = "classpath*:" + BUILTIN_LOCATION_PREFIX + "/" + skillName + "/**";
        String marker = "/" + BUILTIN_LOCATION_PREFIX + "/" + skillName + "/";
        try {
            Resource[] resources = resourceResolver.getResources(pattern);
            List<String> result = new ArrayList<>();
            for (Resource r : resources) {
                if (!r.isReadable()) continue;
                String url = describe(r);
                int idx = url.indexOf(marker);
                if (idx < 0) continue;
                String rel = url.substring(idx + marker.length());
                if (rel.isEmpty() || rel.endsWith("/")) continue;
                if (rel.equals("SKILL.md") || rel.startsWith("scripts/")) continue;
                result.add(rel);
            }
            return result;
        } catch (IOException e) {
            log.warn("[SkillRegistry] listResources(builtin) 失败: name={}, err={}",
                    skillName, e.getMessage());
            return List.of();
        }
    }

    private String readFromClasspath(String skillName, String relativePath) {
        // 规范化校验：禁止 ..、绝对路径（已在前置校验过 startsWith("/")）
        String normalized = Path.of(relativePath).normalize().toString();
        if (normalized.startsWith("..") || normalized.contains("/../") || normalized.equals("..")) {
            throw new SkillResourceException("拒绝路径穿越: " + relativePath);
        }
        String location = "classpath:" + BUILTIN_LOCATION_PREFIX + "/" + skillName + "/" + normalized;
        Resource resource = resourceResolver.getResource(location);
        if (!resource.exists()) {
            throw new SkillResourceException("资源不存在: " + relativePath);
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SkillResourceException("读取失败: " + relativePath, e);
        }
    }

    // ---------- 内部扫描实现 ----------

    private RefreshResult doRefresh() {
        Map<String, Skill> next = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        int builtinCount = scanBuiltin(next, failed);
        int externalCount = scanExternal(next, failed);

        Set<String> previousKeys = this.skills.keySet();
        Set<String> nextKeys = next.keySet();
        List<String> added = nextKeys.stream().filter(k -> !previousKeys.contains(k)).toList();
        List<String> removed = previousKeys.stream().filter(k -> !nextKeys.contains(k)).toList();

        this.skills = Collections.unmodifiableMap(new LinkedHashMap<>(next));
        return new RefreshResult(new ArrayList<>(nextKeys), added, removed, failed, builtinCount, externalCount);
    }

    private int scanBuiltin(Map<String, Skill> sink, List<String> failed) {
        try {
            Resource[] resources = resourceResolver.getResources(BUILTIN_PATTERN);
            int count = 0;
            for (Resource resource : resources) {
                String origin = describe(resource);
                try (InputStream in = resource.getInputStream()) {
                    Skill skill = loader.loadFromClasspath(in, null, origin);
                    putSkill(sink, skill);
                    count++;
                } catch (Exception e) {
                    log.error("[SkillRegistry] builtin skill 加载失败: {} ({})", origin, e.getMessage());
                    failed.add(origin);
                }
            }
            return count;
        } catch (IOException e) {
            log.warn("[SkillRegistry] classpath 扫描 builtin 失败: {}", e.getMessage());
            return 0;
        }
    }

    private int scanExternal(Map<String, Skill> sink, List<String> failed) {
        Path root = Path.of(properties.getPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.info("[SkillRegistry] external skills 目录不存在，跳过: {}", root);
            return 0;
        }
        int count = 0;
        try (Stream<Path> stream = Files.list(root)) {
            for (Path skillDir : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(skillDir)) continue;

                Path skillMd = skillDir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) {
                    log.warn("[SkillRegistry] external 目录缺少 SKILL.md，跳过: {}", skillDir);
                    continue;
                }

                Path scriptsDir = skillDir.resolve("scripts");
                if (Files.isDirectory(scriptsDir)) {
                    log.warn("[SkillRegistry] 检测到 scripts/ 目录，已忽略（dawn-ai 不支持脚本执行）: {}",
                            skillDir);
                }

                try {
                    Skill skill = loader.loadFromFile(skillMd, skillDir.toAbsolutePath().normalize(),
                            Skill.Source.EXTERNAL);
                    putSkill(sink, skill);
                    count++;
                } catch (Exception e) {
                    log.error("[SkillRegistry] external skill 加载失败: {} ({})", skillDir, e.getMessage());
                    failed.add(skillDir.toString());
                }
            }
        } catch (IOException e) {
            log.warn("[SkillRegistry] external skills 目录遍历失败: {}", e.getMessage());
        }
        return count;
    }

    private void putSkill(Map<String, Skill> sink, Skill skill) {
        String name = skill.manifest().name();
        Skill existing = sink.get(name);
        if (existing != null) {
            log.info("[SkillRegistry] 同名 skill '{}' 被 {} 覆盖（原: {}）",
                    name, skill.source(), existing.source());
        }
        sink.put(name, skill);
    }

    private String describe(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException e) {
            return resource.getDescription();
        }
    }

    public record RefreshResult(
            List<String> all,
            List<String> added,
            List<String> removed,
            List<String> failed,
            int builtinCount,
            int externalCount
    ) {
        public int total() {
            return all.size();
        }
    }
}
