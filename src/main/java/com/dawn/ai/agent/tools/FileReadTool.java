package com.dawn.ai.agent.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * 读取指定路径的文件内容。
 *
 * 安全约束：仅允许读取 baseDir 下的文件（防止路径穿越），
 * 读取大小默认限制为 50KB（避免将大文件塞进 LLM 上下文）。
 */
@Slf4j
@Component
@Description("读取指定路径的文件内容。输入文件路径，返回文件文本内容。适用于查看代码、配置文件、日志等。")
public class FileReadTool implements Function<FileReadTool.Request, FileReadTool.Response> {

    private static final long DEFAULT_MAX_BYTES = 50 * 1024; // 50KB

    @Value("${app.tools.file.base-dir:./}")
    private String baseDir;

    @Value("${app.tools.file.max-bytes:" + DEFAULT_MAX_BYTES + "}")
    private long maxBytes;

    public record Request(
            @JsonProperty(required = true)
            @JsonPropertyDescription("要读取的文件路径（相对于项目根目录或绝对路径）")
            String path,

            @JsonProperty(required = false)
            @JsonPropertyDescription("从第几行开始读取（从1开始，默认为1）")
            Integer offset,

            @JsonProperty(required = false)
            @JsonPropertyDescription("最多读取多少行（默认读取全部）")
            Integer limit
    ) {
        public Request(String path) {
            this(path, null, null);
        }
    }

    public record Response(String content, int linesRead, String error) {}

    @Override
    public Response apply(Request req) {
        try {
            Path basePath = Path.of(baseDir).toAbsolutePath().normalize();
            Path target = basePath.resolve(req.path()).normalize();

            // 路径穿越校验
            if (!target.startsWith(basePath)) {
                log.warn("[FileReadTool] Path traversal blocked: {}", req.path());
                return new Response(null, 0, "拒绝访问：路径超出允许范围");
            }

            if (!Files.exists(target)) {
                return new Response(null, 0, "文件不存在: " + req.path());
            }

            if (!Files.isRegularFile(target)) {
                return new Response(null, 0, "路径不是文件: " + req.path());
            }

            long size = Files.size(target);
            if (size > maxBytes) {
                return new Response(null, 0,
                        String.format("文件过大 (%d bytes)，超过限制 (%d bytes)。请使用 offset/limit 参数分段读取。", size, maxBytes));
            }

            String content = Files.readString(target, StandardCharsets.UTF_8);
            String[] lines = content.split("\\R", -1);

            int start = (req.offset() != null && req.offset() > 0) ? req.offset() - 1 : 0;
            int end = (req.limit() != null && req.limit() > 0) ? Math.min(start + req.limit(), lines.length) : lines.length;

            if (start >= lines.length) {
                return new Response(null, 0, "offset 超出文件行数 (" + lines.length + " 行)");
            }

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(String.format("%d\t%s%n", i + 1, lines[i]));
            }

            int linesRead = end - start;
            log.debug("[FileReadTool] Read {} lines from {}", linesRead, req.path());
            return new Response(sb.toString(), linesRead, null);

        } catch (IOException e) {
            log.error("[FileReadTool] Failed to read {}: {}", req.path(), e.getMessage());
            return new Response(null, 0, "读取失败: " + e.getMessage());
        }
    }
}
