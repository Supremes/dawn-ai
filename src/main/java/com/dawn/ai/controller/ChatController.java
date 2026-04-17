package com.dawn.ai.controller;

import com.dawn.ai.dto.ChatRequest;
import com.dawn.ai.dto.ChatResponse;
import com.dawn.ai.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * Standard chat endpoint with memory + optional RAG + tool calling.
     */
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("[ChatController] Incoming chat request, sessionId={}", request.getSessionId());
        ChatResponse response = chatService.chat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Simple one-shot chat, no memory, no tools.
     */
    @GetMapping("/simple")
    public ResponseEntity<String> simpleChat(@RequestParam String message) {
        return ResponseEntity.ok(chatService.simpleChat(message));
    }

    /**
     * SSE streaming chat endpoint.
     *
     * <p>Returns a stream of server-sent events in the order:
     * {@code connected → plan? → step* → token* → done | error}.
     *
     * <p>Use {@code fetch()} on the client side rather than {@code EventSource} because
     * this endpoint accepts a POST body (same {@link ChatRequest} as the sync endpoint).
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request) {
        log.info("[ChatController] Incoming stream request, sessionId={}", request.getSessionId());
        return chatService.streamChat(request);
    }
}
