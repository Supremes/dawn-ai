package com.dawn.ai.agent.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 搜索文件内容中的正则匹配。
 *
 * 类似 grep -rn，遍历目录下所有文件，返回匹配行及其行号。
 * 默认限制 100 条结果、单文件最大 1MB，跳过二进制文件。
 */
@Slf4j
@Component
@Description("在文件内容中搜索正则表达式。输入 regex 模式和可选的搜索目录，返回匹配的文件、行号和内容。适用于查找代码引用、错误日志、配置项等。")
public class GrepTool implements Function<GrepTool.Request, GrepTool.Response> {

    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final long DEFAULT_MAX_FILE_SIZE = 1024 * 1024; // 1MB
    private static final List<String> SKIP_DIRS = List.of(".git", "node_modules", "target", "build", ".idea", ".vscode");
    private static final List<String> SKIP_EXTS = List.of(".class", ".jar", ".war", ".zip", ".gz", ".tar", ".png", ".jpg", ".gif", ".ico", ".woff", ".woff2", ".ttf", ".eot");

    @Value("${app.tools.file.base-dir:./}")
    private String baseDir;

    @Value("${app.tools.grep.max-results:" + DEFAULT_MAX_RESULTS + "}")
    private int maxResults;

    public record Request(
            @JsonProperty(required = true)
            @JsonPropertyDescription("要搜索的正则表达式")
            String pattern,

            @JsonProperty(required = false)
            @JsonPropertyDescription("搜索目录（默认为项目根目录）")
            String directory,

            @JsonProperty(required = false)
            @JsonPropertyDescription("文件名 glob 过滤（如 *.java），不设置则搜索所有文件")
            String fileGlob,

            @JsonProperty(required = false)
            @JsonPropertyDescription("是否区分大小写（默认不区分）")
            Boolean caseSensitive
    ) {
        public Request(String pattern) {
            this(pattern, null, null, null);
        }
    }

    public record Match(String file, int lineNumber, String line) {}
    public record Response(List<Match> matches, int totalFound, String error) {}

    @Override
    public Response apply(Request req) {
        try {
            Path basePath = Path.of(baseDir).toAbsolutePath().normalize();
            Path searchDir = (req.directory() != null && !req.directory().isBlank())
                    ? basePath.resolve(req.directory()).normalize()
                    : basePath;

            if (!searchDir.startsWith(basePath)) {
                log.warn("[GrepTool] Path traversal blocked: {}", req.directory());
                return new Response(List.of(), 0, "拒绝访问：路径超出允许范围");
            }

            if (!Files.isDirectory(searchDir)) {
                return new Response(List.of(), 0, "目录不存在: " + (req.directory() != null ? req.directory() : "."));
            }

            int flags = Pattern.MULTILINE;
            if (req.caseSensitive() == null || !req.caseSensitive()) {
                flags |= Pattern.CASE_INSENSITIVE;
            }
            Pattern regex = Pattern.compile(req.pattern(), flags);

            var fileMatcher = (req.fileGlob() != null && !req.fileGlob().isBlank())
                    ? FileSystems.getDefault().getPathMatcher("glob:" + req.fileGlob())
                    : null;

            List<Match> matches = new ArrayList<>();
            int[] totalFound = {0};

            Files.walkFileTree(searchDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (SKIP_DIRS.contains(dirName) || dirName.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= maxResults) return FileVisitResult.TERMINATE;

                    String fileName = file.getFileName().toString();
                    String ext = getExtension(fileName);
                    if (SKIP_EXTS.contains(ext)) return FileVisitResult.CONTINUE;

                    if (fileMatcher != null && !fileMatcher.matches(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }

                    try {
                        long size = Files.size(file);
                        if (size > DEFAULT_MAX_FILE_SIZE || size == 0) return FileVisitResult.CONTINUE;

                        // 快速检测是否为文本文件
                        if (!isProbablyText(file)) return FileVisitResult.CONTINUE;

                        String content = Files.readString(file, StandardCharsets.UTF_8);
                        String[] lines = content.split("\\R", -1);
                        Path relativePath = basePath.relativize(file);

                        for (int i = 0; i < lines.length && matches.size() < maxResults; i++) {
                            if (regex.matcher(lines[i]).find()) {
                                matches.add(new Match(relativePath.toString(), i + 1, lines[i].trim()));
                                totalFound[0]++;
                            }
                        }
                    } catch (Exception e) {
                        // 跳过无法读取的文件
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            log.debug("[GrepTool] pattern='{}', dir='{}', found={}", req.pattern(), searchDir, totalFound[0]);
            return new Response(matches, totalFound[0], null);

        } catch (IOException e) {
            log.error("[GrepTool] Failed: {}", e.getMessage());
            return new Response(List.of(), 0, "搜索失败: " + e.getMessage());
        }
    }

    private boolean isProbablyText(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) return true;
            // 检查前 512 字节是否包含 null 字节（二进制文件特征）
            int checkLen = Math.min(bytes.length, 512);
            for (int i = 0; i < checkLen; i++) {
                if (bytes[i] == 0) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot) : "";
    }
}
