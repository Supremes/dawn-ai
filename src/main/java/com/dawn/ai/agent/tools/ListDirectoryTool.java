package com.dawn.ai.agent.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 列出目录内容。
 *
 * 默认展示指定目录下的文件和子目录（不递归），可设置递归深度。
 * 结果以树形结构格式化输出，便于 LLM 理解项目结构。
 */
@Slf4j
@Component
@Description("列出指定目录下的文件和子目录。输入目录路径和可选的递归深度，返回目录结构。适用于了解项目结构、查看文件列表。")
public class ListDirectoryTool implements Function<ListDirectoryTool.Request, ListDirectoryTool.Response> {

    @Value("${app.tools.file.base-dir:./}")
    private String baseDir;

    public record Request(
            @JsonProperty(required = false)
            @JsonPropertyDescription("要列出的目录路径（默认为项目根目录）")
            String path,

            @JsonProperty(required = false)
            @JsonPropertyDescription("递归深度（0=仅当前目录，1=一层子目录，默认为0）")
            Integer depth
    ) {
        public Request() {
            this(null, null);
        }
    }

    public record Response(String tree, int fileCount, int dirCount, String error) {}

    @Override
    public Response apply(Request req) {
        try {
            Path basePath = Path.of(baseDir).toAbsolutePath().normalize();
            Path target = (req.path() != null && !req.path().isBlank())
                    ? basePath.resolve(req.path()).normalize()
                    : basePath;

            if (!target.startsWith(basePath)) {
                log.warn("[ListDirectoryTool] Path traversal blocked: {}", req.path());
                return new Response(null, 0, 0, "拒绝访问：路径超出允许范围");
            }

            if (!Files.isDirectory(target)) {
                return new Response(null, 0, 0, "目录不存在: " + (req.path() != null ? req.path() : "."));
            }

            int maxDepth = (req.depth() != null && req.depth() >= 0) ? req.depth() : 0;
            int[] counters = {0, 0}; // [files, dirs]

            StringBuilder sb = new StringBuilder();
            String relativeBase = basePath.relativize(target).toString();
            sb.append(relativeBase.isEmpty() ? "." : relativeBase).append("/\n");

            buildTree(target, basePath, sb, "", maxDepth, 0, counters);

            log.debug("[ListDirectoryTool] path='{}', files={}, dirs={}", req.path(), counters[0], counters[1]);
            return new Response(sb.toString(), counters[0], counters[1], null);

        } catch (IOException e) {
            log.error("[ListDirectoryTool] Failed: {}", e.getMessage());
            return new Response(null, 0, 0, "列出目录失败: " + e.getMessage());
        }
    }

    private void buildTree(Path dir, Path basePath, StringBuilder sb, String prefix, int maxDepth, int currentDepth, int[] counters) throws IOException {
        List<Path> entries;
        try (Stream<Path> stream = Files.list(dir)) {
            entries = stream
                    .sorted((a, b) -> {
                        boolean aDir = Files.isDirectory(a);
                        boolean bDir = Files.isDirectory(b);
                        if (aDir != bDir) return aDir ? -1 : 1;
                        return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                    })
                    .toList();
        }

        for (int i = 0; i < entries.size(); i++) {
            Path entry = entries.get(i);
            String name = entry.getFileName().toString();
            boolean isLast = (i == entries.size() - 1);
            boolean isDir = Files.isDirectory(entry);

            sb.append(prefix).append(isLast ? "└── " : "├── ").append(name);
            if (isDir) sb.append("/");
            sb.append("\n");

            if (isDir) {
                counters[1]++;
                if (currentDepth < maxDepth) {
                    String newPrefix = prefix + (isLast ? "    " : "│   ");
                    buildTree(entry, basePath, sb, newPrefix, maxDepth, currentDepth + 1, counters);
                }
            } else {
                counters[0]++;
            }
        }
    }
}
