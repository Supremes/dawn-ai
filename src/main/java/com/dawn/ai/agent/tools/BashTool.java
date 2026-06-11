package com.dawn.ai.agent.tools;

import com.dawn.ai.agent.trace.StepCollector;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 通用 Bash 命令执行工具。
 *
 * 通过 /bin/bash -c 在宿主机执行 shell 命令，替代 FileReadTool / GrepTool /
 * GlobTool / ListDirectoryTool，同时支持 git、wc、curl 等任意非交互命令。
 *
 * 安全约束：
 * - 命令黑名单拦截高危操作（rm -rf /、mkfs、shutdown 等）
 * - 工作目录锁定在 baseDir
 * - 执行超时（默认 30 秒）
 * - 输出截断（默认 50KB，防止撑爆 LLM 上下文）
 */
@Slf4j
@Component
@Description("执行 bash 命令并返回输出。可用于文件读取(cat/head/tail)、内容搜索(grep)、文件查找(find)、目录浏览(ls/tree)、git 操作等。输入要执行的命令字符串，返回 stdout 和 stderr。仅支持非交互式命令。")
public class BashTool implements Function<BashTool.Request, BashTool.Response> {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 50 * 1024; // 50KB

    private static final Set<String> BLOCKED_COMMANDS = Set.of(
            "shutdown", "reboot", "poweroff", "halt", "init",
            "mkfs", "fdisk", "dd",
            "passwd", "useradd", "userdel", "usermod",
            "iptables", "nft",
            "systemctl", "service"
    );

    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
            Pattern.compile("rm\\s+(-[^\\s]*)?\\s*/\\s*$"),       // rm / or rm -rf /
            Pattern.compile("rm\\s+-[^\\s]*r[^\\s]*f[^\\s]*\\s+/"), // rm -rf /...
            Pattern.compile("chmod\\s+-R\\s+777\\s+/"),            // chmod -R 777 /
            Pattern.compile(":(\\s*)\\{\\s*:\\|:\\s*&\\s*\\}"),     // fork bomb :(){ :|:& };:
            Pattern.compile(">\\.?\\s*/dev/[sh]da")                // overwrite disk device
    );

    @Value("${app.tools.file.base-dir:./}")
    private String baseDir;

    @Value("${app.tools.bash.timeout-seconds:" + DEFAULT_TIMEOUT_SECONDS + "}")
    private int timeoutSeconds;

    @Value("${app.tools.bash.max-output-bytes:" + DEFAULT_MAX_OUTPUT_BYTES + "}")
    private int maxOutputBytes;

    @Value("${app.tools.bash.max-consecutive-failures:3}")
    private int maxConsecutiveFailures;

    public record Request(
            @JsonProperty(required = true)
            @JsonPropertyDescription("要执行的 bash 命令")
            String command,

            @JsonProperty(required = false)
            @JsonPropertyDescription("执行超时秒数（默认 30 秒，最大 300 秒）")
            Integer timeout
    ) {
        public Request(String command) {
            this(command, null);
        }
    }

    public record Response(String stdout, int exitCode, String error) {}

    @Override
    public Response apply(Request req) {
        if (req.command() == null || req.command().isBlank()) {
            return withStopObservationIfNeeded(new Response(null, -1, "命令不能为空"));
        }

        String command = req.command().trim();

        // 安全检查
        String securityError = checkSecurity(command);
        if (securityError != null) {
            log.warn("[BashTool] 命令被拦截: {} — 原因: {}", command, securityError);
            return withStopObservationIfNeeded(new Response(null, -1, securityError));
        }

        int effectiveTimeout = resolveTimeout(req.timeout());
        Path workDir = Path.of(baseDir).toAbsolutePath().normalize();

        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            pb.environment().put("LC_ALL", "en_US.UTF-8");

            long startMs = System.currentTimeMillis();
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            boolean truncated = false;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int bytesRead;
                while ((bytesRead = reader.read(buf)) != -1) {
                    if (output.length() + bytesRead > maxOutputBytes) {
                        int remaining = maxOutputBytes - output.length();
                        if (remaining > 0) {
                            output.append(buf, 0, remaining);
                        }
                        truncated = true;
                        break;
                    }
                    output.append(buf, 0, bytesRead);
                }
            }

            boolean completed = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);
            long durationMs = System.currentTimeMillis() - startMs;

            if (!completed) {
                process.destroyForcibly();
                log.warn("[BashTool] 命令超时 ({}s): {}", effectiveTimeout, command);
                String partial = output.toString();
                return withStopObservationIfNeeded(new Response(
                        partial.isEmpty() ? null : partial,
                        -1,
                        String.format("命令执行超时（%d 秒）。已强制终止。", effectiveTimeout)
                ));
            }

            int exitCode = process.exitValue();
            String stdout = output.toString();

            if (truncated) {
                stdout += "\n... [输出截断，已达 " + maxOutputBytes / 1024 + "KB 上限]";
            }

            log.debug("[BashTool] cmd='{}', exitCode={}, outputLen={}, {}ms",
                    command.length() > 80 ? command.substring(0, 80) + "..." : command,
                    exitCode, stdout.length(), durationMs);

            return withStopObservationIfNeeded(new Response(stdout, exitCode, exitCode != 0 ? "命令退出码: " + exitCode : null));

        } catch (Exception e) {
            log.error("[BashTool] 执行失败: {} — {}", command, e.getMessage());
            return withStopObservationIfNeeded(new Response(null, -1, "执行失败: " + e.getMessage()));
        }
    }

    private Response withStopObservationIfNeeded(Response response) {
        boolean failedOrEmpty = response.exitCode() != 0
                || (response.stdout() == null || response.stdout().isBlank());
        if (!StepCollector.recordBashObservation(failedOrEmpty, maxConsecutiveFailures)) {
            return response;
        }
        String stopMessage = "BashTool 连续 " + maxConsecutiveFailures +
                " 次失败或无输出。本轮不要再调用 BashTool；请基于已有观察总结，" +
                "如果仍无法确认事实，请明确说明无法确认。";
        String error = response.error() == null || response.error().isBlank()
                ? stopMessage
                : stopMessage + " 最后一次错误: " + response.error();
        return new Response(response.stdout(), response.exitCode(), error);
    }

    private String checkSecurity(String command) {
        String baseCommand = command.split("\\s+")[0];

        if (BLOCKED_COMMANDS.contains(baseCommand)) {
            return "禁止执行危险命令: " + baseCommand;
        }

        for (Pattern p : BLOCKED_PATTERNS) {
            if (p.matcher(command).find()) {
                return "命令匹配危险模式，已拦截";
            }
        }

        return null;
    }

    private int resolveTimeout(Integer requested) {
        if (requested == null || requested <= 0) {
            return timeoutSeconds;
        }
        return Math.min(requested, 300);
    }
}
