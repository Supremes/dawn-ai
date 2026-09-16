package com.dawn.ai.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 把每一次发出去的 AI HTTP 请求和收到的响应都写到
 * {@code AI_INTERACTION} 文件 logger 里（滚动 log，见 logback-spring.xml）。
 *
 * <p>前端通过
 * {@link com.dawn.ai.controller.AiInteractionController#tailLog} 读这个 log 文件。
 */
@Component
public class AiInteractionLogger {

    private static final Logger FILE_LOG = LoggerFactory.getLogger("AI_INTERACTION");
    private static final int MAX_BODY_CHARS_IN_FILE = 12_000;
    private static final String UNKNOWN_SESSION = "no-session";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void logRequest(String sessionId, String method, String url, String body) {
        write(Entry.builder()
                .id(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .sessionId(normalize(sessionId))
                .direction("request")
                .method(method)
                .url(url)
                .body(body)
                .build());
    }

    public void logResponse(String sessionId, int status, String body, long latencyMs) {
        write(Entry.builder()
                .id(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .sessionId(normalize(sessionId))
                .direction("response")
                .status(status)
                .latencyMs(latencyMs)
                .body(body)
                .build());
    }

    /**
     * 应用层面的记录，给那些没法对应到单次 HTTP 往返的事件用
     * （比如流式聊天最后聚合出来的完整回答）。
     */
    public void logLogical(String sessionId, String direction, String label, String body, Long latencyMs) {
        write(Entry.builder()
                .id(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .sessionId(normalize(sessionId))
                .direction(direction)
                .summary(label)
                .body(body)
                .latencyMs(latencyMs)
                .build());
    }

    private void write(Entry entry) {
        FILE_LOG.info(toJson(entry));
    }

    private String toJson(Entry entry) {
        try {
            ObjectNode node = objectMapper.valueToTree(entry);
            String body = entry.getBody();
            if (body != null && body.length() > MAX_BODY_CHARS_IN_FILE) {
                node.put("body", body.substring(0, MAX_BODY_CHARS_IN_FILE)
                        + "…[truncated " + (body.length() - MAX_BODY_CHARS_IN_FILE) + " chars]");
            }
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"failed to serialize entry\"}";
        }
    }

    private String normalize(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? UNKNOWN_SESSION : sessionId;
    }

    @Value
    @Builder
    public static class Entry {
        String id;
        String timestamp;
        String sessionId;
        String direction;     // "request" | "response"
        String method;
        String url;
        Integer status;
        Long latencyMs;
        String summary;
        String body;
    }
}
