package com.dawn.ai.agent.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 按 glob 模式查找文件。
 *
 * 使用 Java NIO 的 PathMatcher 实现，支持 Ant 风格模式如：
 * **&#47;*.java, src/**&#47;*Test*.java, **&#47;*.yml
 *
 * 结果默认限制 200 条，防止大仓库返回过多文件。
 */
@Slf4j
@Component
@Description("按 glob 模式查找文件。输入 glob 模式（如 **/*.java, src/**/*.yml），返回匹配的文件路径列表。适用于定位代码文件、配置文件等。")
public class GlobTool implements Function<GlobTool.Request, GlobTool.Response> {

    private static final int DEFAULT_MAX_RESULTS = 200;

    @Value("${app.tools.file.base-dir:./}")
    private String baseDir;

    @Value("${app.tools.glob.max-results:" + DEFAULT_MAX_RESULTS + "}")
    private int maxResults;

    public record Request(
            @JsonProperty(required = true)
            @JsonPropertyDescription("glob 模式，如 **/*.java, src/**/*.yml, **/README.md")
            String pattern,

            @JsonProperty(required = false)
            @JsonPropertyDescription("搜索起始目录（默认为项目根目录）")
            String directory
    ) {
        public Request(String pattern) {
            this(pattern, null);
        }
    }

    public record Response(List<String> files, int totalFound, String error) {}

    @Override
    public Response apply(Request req) {
        try {
            Path basePath = Path.of(baseDir).toAbsolutePath().normalize();
            Path searchDir = (req.directory() != null && !req.directory().isBlank())
                    ? basePath.resolve(req.directory()).normalize()
                    : basePath;

            // 路径穿越校验
            if (!searchDir.startsWith(basePath)) {
                log.warn("[GlobTool] Path traversal blocked: {}", req.directory());
                return new Response(List.of(), 0, "拒绝访问：路径超出允许范围");
            }

            if (!Files.isDirectory(searchDir)) {
                return new Response(List.of(), 0, "目录不存在: " + (req.directory() != null ? req.directory() : req.pattern()));
            }

            var matcher = FileSystems.getDefault().getPathMatcher("glob:" + req.pattern());
            List<String> matched;
            int totalFound;

            try (Stream<Path> walk = Files.walk(searchDir)) {
                List<Path> all = walk
                        .filter(Files::isRegularFile)
                        .filter(p -> matcher.matches(searchDir.relativize(p)))
                        .limit(maxResults + 1)
                        .toList();

                totalFound = all.size();
                matched = all.stream()
                        .limit(maxResults)
                        .map(basePath::relativize)
                        .map(Path::toString)
                        .toList();
            }

            log.debug("[GlobTool] pattern='{}', dir='{}', found={}", req.pattern(), searchDir, totalFound);
            return new Response(matched, totalFound, null);

        } catch (IOException e) {
            log.error("[GlobTool] Failed: {}", e.getMessage());
            return new Response(List.of(), 0, "搜索失败: " + e.getMessage());
        }
    }
}
