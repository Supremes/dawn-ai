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
 * Records every outgoing AI HTTP request and incoming response to the
 * {@code AI_INTERACTION} file logger (rolling, see logback-spring.xml).
 *
 * <p>The frontend reads the resulting log file via
 * {@link com.dawn.ai.controller.AiInteractionController#tailLog}.
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
     * Application-level entry for events that don't map cleanly to a single HTTP
     * exchange (e.g. final aggregated answer of a streaming chat).
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
