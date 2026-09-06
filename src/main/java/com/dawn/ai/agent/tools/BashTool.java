package com.dawn.ai.agent.tools;

import com.dawn.ai.agent.trace.StepCollector;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * 安全约束（三档分级）：
 * - 硬红线：系统级不可逆破坏命令（shutdown/mkfs/dd、rm -rf / 等）永远拒绝，配置开关无法解除
 * - 写操作（rm/mv/cp/chmod/重定向写入等）由 app.tools.bash.allow-write 控制，默认 false（只读安全模式），需在配置中显式开启
 * - 只读 / 网络命令默认放行
 * - 安全检查会拆解整条命令串逐子命令判定，防止 `a; mkfs` 这类组合绕过
 * - 工作目录锁定在 baseDir；执行超时（默认 30 秒）；输出截断（默认 50KB）
 * - 写/删除等高风险操作的语义提示由 system prompt 安全准则 + 对话确认承担（软性纵深防御；真正的执行门控是上面 allow-write 的只读安全模式）
 */
@Slf4j
@Component
@Description("执行 bash 命令并返回输出。可用于文件读取(cat/head/tail)、内容搜索(grep)、文件查找(find)、目录浏览(ls/tree)、git 操作等。输入要执行的命令字符串，返回 stdout 和 stderr。仅支持非交互式命令。执行写入、删除、移动文件或其他有副作用的操作前，必须先向用户说明并取得明确同意。")
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

    /** 写/删除类命令：受 app.tools.bash.allow-write 控制（默认放行）。 */
    private static final Set<String> WRITE_COMMANDS = Set.of(
            "rm", "rmdir", "mv", "cp", "tee", "truncate", "shred",
            "chmod", "chown", "chgrp", "ln", "install", "mkdir", "touch"
    );

    /** 透明包装器：跳过后取其后的真实命令做判定（如 sudo shutdown）。 */
    private static final Set<String> WRAPPER_COMMANDS = Set.of(
            "sudo", "env", "nohup", "time", "nice", "ionice"
    );

    /** 子命令分隔符：; | & && ||、换行，用于拆解组合命令逐段判定。 */
    private static final String SUBCOMMAND_DELIMITERS = "\\|\\||&&|[;|&\\n]";

    /** 写重定向：> 或 >>（排除 2>&1 这类 fd 复制）。 */
    private static final Pattern REDIRECT_WRITE = Pattern.compile(">>?\\s*(?![&])\\S");

    @Value("${app.tools.file.base-dir:./}")
    private String baseDir;

    @Value("${app.tools.bash.timeout-seconds:" + DEFAULT_TIMEOUT_SECONDS + "}")
    private int timeoutSeconds;

    @Value("${app.tools.bash.max-output-bytes:" + DEFAULT_MAX_OUTPUT_BYTES + "}")
    private int maxOutputBytes;

    @Value("${app.tools.bash.max-consecutive-failures:3}")
    private int maxConsecutiveFailures;

    @Value("${app.tools.bash.allow-write:false}")
    private boolean allowWrite;

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

    public record Response(
            String stdout,
            int exitCode,
            String error,
            @JsonIgnore ToolOutcomeStatus outcomeStatus
    ) implements ToolOutcome {

        public Response(String stdout, int exitCode, String error) {
            this(stdout, exitCode, error, defaultStatus(stdout, exitCode));
        }

        private static ToolOutcomeStatus defaultStatus(String stdout, int exitCode) {
            if (exitCode != 0) {
                return ToolOutcomeStatus.PERMANENT_FAILURE;
            }
            return stdout == null || stdout.isBlank()
                    ? ToolOutcomeStatus.EMPTY
                    : ToolOutcomeStatus.SUCCESS;
        }
    }

    @Override
    public Response apply(Request req) {
        if (StepCollector.isBashCircuitOpen()) {
            return new Response(
                    null,
                    -1,
                    "BashTool 本轮已因连续失败停止执行，请改用已有观察回答或明确说明无法确认。",
                    ToolOutcomeStatus.REFUSED);
        }

        if (req.command() == null || req.command().isBlank()) {
            return withStopObservationIfNeeded(new Response(
                    null, -1, "命令不能为空", ToolOutcomeStatus.PERMANENT_FAILURE));
        }

        String command = req.command().trim();

        // 安全检查
        String securityError = checkSecurity(command);
        if (securityError != null) {
            log.warn("[BashTool] 命令被拦截: {} — 原因: {}", command, securityError);
            return withStopObservationIfNeeded(new Response(
                    null, -1, securityError, ToolOutcomeStatus.REFUSED));
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
                        String.format("命令执行超时（%d 秒）。已强制终止。", effectiveTimeout),
                        ToolOutcomeStatus.RETRYABLE_FAILURE
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

            ToolOutcomeStatus outcome = exitCode != 0
                    ? ToolOutcomeStatus.PERMANENT_FAILURE
                    : (stdout.isBlank() ? ToolOutcomeStatus.EMPTY : ToolOutcomeStatus.SUCCESS);
            return withStopObservationIfNeeded(new Response(
                    stdout,
                    exitCode,
                    exitCode != 0 ? "命令退出码: " + exitCode : null,
                    outcome));

        } catch (Exception e) {
            log.error("[BashTool] 执行失败: {} — {}", command, e.getMessage());
            return withStopObservationIfNeeded(new Response(
                    null,
                    -1,
                    "执行失败: " + e.getMessage(),
                    ToolOutcomeStatus.RETRYABLE_FAILURE));
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
        return new Response(response.stdout(), response.exitCode(), error, response.outcomeStatus());
    }

    private String checkSecurity(String command) {
        // ① 硬红线：整串危险模式（不可逆系统破坏），任何配置都不能解除
        for (Pattern p : BLOCKED_PATTERNS) {
            if (p.matcher(command).find()) {
                return "命令匹配危险模式，已拦截";
            }
        }

        // ② 拆解整条命令串，逐子命令取 base command 判定（防 `a; mkfs` 组合绕过）
        boolean hasWriteOp = REDIRECT_WRITE.matcher(command).find();
        for (String sub : command.split(SUBCOMMAND_DELIMITERS)) {
            String base = baseCommandOf(sub);
            if (base.isEmpty()) {
                continue;
            }
            if (BLOCKED_COMMANDS.contains(base)) {
                return "禁止执行危险命令: " + base;
            }
            if (WRITE_COMMANDS.contains(base)) {
                hasWriteOp = true;
            }
        }

        // ③ 写操作门控：默认只读安全；仅当显式开启 allow-write 时才放行
        if (hasWriteOp && !allowWrite) {
            return "检测到写/删除类操作，当前为只读安全模式（app.tools.bash.allow-write=false），已拒绝执行。"
                    + "如确需执行该写操作，请由用户在受信任的配置中显式开启 app.tools.bash.allow-write；否则请改用只读命令完成任务。";
        }

        return null;
    }

    /** 取子命令的 base command：剥离路径、跳过 env 赋值前缀、透明包装器与 flag。 */
    private String baseCommandOf(String sub) {
        for (String token : sub.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
                continue; // 环境变量赋值前缀，如 LC_ALL=C
            }
            String base = stripPath(token);
            if (WRAPPER_COMMANDS.contains(base) || base.startsWith("-")) {
                continue; // 包装器或 flag，继续看下一个 token
            }
            return base;
        }
        return "";
    }

    /** /sbin/shutdown -> shutdown，挡掉绝对路径绕过。 */
    private String stripPath(String token) {
        int idx = token.lastIndexOf('/');
        return idx >= 0 ? token.substring(idx + 1) : token;
    }

    private int resolveTimeout(Integer requested) {
        if (requested == null || requested <= 0) {
            return timeoutSeconds;
        }
        return Math.min(requested, 300);
    }
}
