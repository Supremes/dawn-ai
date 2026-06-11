package com.dawn.ai.controller;

import com.dawn.ai.dto.ChatRequest;
import com.dawn.ai.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

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
