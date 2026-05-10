package com.dawn.ai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Exposes the {@code ai_interaction.log} file (written by {@link com.dawn.ai.config.AiInteractionLogger})
 * as plain text or browser-friendly pretty-printed HTML for the frontend
 * "Interactions" link to view.
 *
 * <p>Endpoint: {@code GET /api/v1/ai-interactions/log[?tail=N&sessionId=xxx]}
 *
 * <ul>
 *   <li>{@code tail} — number of trailing lines to return (default 500, max 5000).</li>
 *   <li>{@code sessionId} — optional, return only lines whose JSON contains the matching sessionId.</li>
 *   <li>{@code pretty} — optional, render the response as HTML with pretty-printed JSON.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai-interactions")
@RequiredArgsConstructor
public class AiInteractionController {

    private static final int DEFAULT_TAIL = 500;
    private static final int MAX_TAIL = 5_000;
    private static final MediaType HTML_UTF8 = MediaType.parseMediaType("text/html;charset=UTF-8");

    private final ObjectMapper objectMapper;

    @Value("${app.ai.interaction-log.path:logs/ai_interaction.log}")
    private String logPath;

    @GetMapping(value = "/log", produces = {MediaType.TEXT_PLAIN_VALUE, MediaType.TEXT_HTML_VALUE})
    public ResponseEntity<String> tailLog(
            @RequestParam(required = false, defaultValue = "500") int tail,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false, defaultValue = "false") boolean pretty) {

        int requested = Math.max(1, Math.min(tail <= 0 ? DEFAULT_TAIL : tail, MAX_TAIL));

        Path file = Paths.get(logPath);
        if (!Files.exists(file)) {
            String message = "(no interactions yet — file " + file.toAbsolutePath() + " not created)";
            return pretty ? htmlResponse("AI Interaction Log", message) : plainTextResponse(message);
        }

        try {
            List<String> lines = readLastLines(file, requested);
            boolean filtering = sessionId != null && !sessionId.isBlank();
            if (filtering) {
                String needle = "\"sessionId\":\"" + sessionId + "\"";
                lines = lines.stream().filter(l -> l.contains(needle)).toList();
            }
            if (lines.isEmpty()) {
                String message = filtering
                        ? "(no entries for sessionId=" + sessionId + " in last " + requested + " lines)"
                        : "(log file is empty — no AI interactions recorded yet)";
                return pretty ? htmlResponse("AI Interaction Log", message) : plainTextResponse(message);
            }
            if (pretty) {
                String title = filtering
                        ? "AI Interaction Log — sessionId=" + sessionId
                        : "AI Interaction Log";
                return htmlResponse(title, buildPrettyLog(lines));
            }
            return plainTextResponse(String.join("\n", lines));
        } catch (IOException e) {
            log.warn("[AiInteractionController] failed to tail {}: {}", file, e.getMessage());
            return ResponseEntity.status(500).body("Failed to read log file: " + e.getMessage());
        }
    }

    private ResponseEntity<String> plainTextResponse(String body) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(body + "\n");
    }

    private ResponseEntity<String> htmlResponse(String title, String content) {
        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>%s</title>
                    <style>
                        :root {
                            color-scheme: light dark;
                        }
                        body {
                            margin: 0;
                            padding: 24px;
                            font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
                            background: #0b1020;
                            color: #e5e7eb;
                        }
                        .log-shell {
                            max-width: 1200px;
                            margin: 0 auto;
                        }
                        h1 {
                            margin: 0 0 16px;
                            font-size: 20px;
                        }
                        .log-output {
                            margin: 0;
                            padding: 20px;
                            white-space: pre-wrap;
                            word-break: break-word;
                            background: rgba(15, 23, 42, 0.92);
                            border: 1px solid rgba(148, 163, 184, 0.25);
                            border-radius: 12px;
                            line-height: 1.5;
                        }
                    </style>
                </head>
                <body>
                    <main class="log-shell">
                        <h1>%s</h1>
                        <pre class="log-output">%s</pre>
                    </main>
                </body>
                </html>
                """.formatted(escapeHtml(title), escapeHtml(title), escapeHtml(content));
        return ResponseEntity.ok()
                .contentType(HTML_UTF8)
                .body(html);
    }

    private String buildPrettyLog(List<String> lines) {
        return lines.stream()
                .map(this::prettyPrintLine)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    private String prettyPrintLine(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(expandEmbeddedJson(node));
        } catch (IOException e) {
            return line;
        }
    }

    private JsonNode expandEmbeddedJson(JsonNode node) {
        if (!(node instanceof ObjectNode objectNode)) {
            return node;
        }
        ObjectNode copy = objectNode.deepCopy();
        JsonNode bodyNode = copy.get("body");
        if (bodyNode != null && bodyNode.isTextual()) {
            JsonNode parsedBody = tryParseJson(bodyNode.asText());
            if (parsedBody != null) {
                copy.set("body", parsedBody);
            }
        }
        return copy;
    }

    private JsonNode tryParseJson(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        char first = trimmed.charAt(0);
        if (first != '{' && first != '[') {
            return null;
        }
        try {
            return objectMapper.readTree(trimmed);
        } catch (IOException e) {
            return null;
        }
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Read the last {@code n} lines of {@code file} efficiently by scanning backwards
     * through the file in 8 KB chunks. Avoids loading the whole file into memory.
     */
    private List<String> readLastLines(Path file, int n) throws IOException {
        Deque<String> buffer = new ArrayDeque<>(n);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long pos = raf.length();
            byte[] chunk = new byte[8 * 1024];
            StringBuilder leftover = new StringBuilder();
            while (pos > 0 && buffer.size() < n) {
                int read = (int) Math.min(chunk.length, pos);
                pos -= read;
                raf.seek(pos);
                raf.readFully(chunk, 0, read);
                String text = new String(chunk, 0, read, StandardCharsets.UTF_8);
                String combined = text + leftover;
                String[] parts = combined.split("\n", -1);
                // first segment may be partial — keep for next iteration
                leftover.setLength(0);
                leftover.append(parts[0]);
                for (int i = parts.length - 1; i >= 1 && buffer.size() < n; i--) {
                    if (!parts[i].isEmpty()) buffer.addFirst(parts[i]);
                }
            }
            if (leftover.length() > 0 && buffer.size() < n) {
                buffer.addFirst(leftover.toString());
            }
        }
        return new ArrayList<>(buffer);
    }
}
