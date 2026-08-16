package com.dawn.ai.controller;

import com.dawn.ai.agent.registry.ToolRegistry;
import com.dawn.ai.agent.skill.SkillRegistry;
import com.dawn.ai.dto.ChatRequest;
import com.dawn.ai.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;

    public record CapabilityItem(String name, String description, boolean defaultEnabled) {}

    public record CapabilityCatalog(List<CapabilityItem> tools, List<CapabilityItem> skills) {}

    @GetMapping("/capabilities")
    public CapabilityCatalog capabilities() {
        List<CapabilityItem> tools = toolRegistry.getDescriptions().entrySet().stream()
                .filter(entry -> !ToolRegistry.INTERNAL_TOOL_NAMES.contains(entry.getKey()))
                .map(entry -> new CapabilityItem(entry.getKey(), entry.getValue(), true))
                .sorted(java.util.Comparator.comparing(CapabilityItem::name))
                .toList();
        List<CapabilityItem> skills = skillRegistry.list().stream()
                .map(skill -> new CapabilityItem(
                        skill.manifest().name(),
                        skill.manifest().description(),
                        true))
                .sorted(java.util.Comparator.comparing(CapabilityItem::name))
                .toList();
        return new CapabilityCatalog(tools, skills);
    }

    /**
     * SSE streaming chat endpoint.
     *
     * <p>Returns a stream of server-sent events in the order:
     * {@code connected → plan_thinking* → plan? → thinking* → step* → token* → done | error}
     * (see {@link com.dawn.ai.sse.ChatStreamEvent} for the canonical sequence).
     *
     * <p>Use {@code fetch()} on the client side rather than {@code EventSource} because
     * this endpoint accepts a POST body (same {@link ChatRequest} as the sync endpoint).
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request) {
        log.info("[ChatController] Incoming stream request, sessionId={}, query={}", request.getSessionId(), request.getMessage());
        return chatService.streamChat(request);
    }
}
