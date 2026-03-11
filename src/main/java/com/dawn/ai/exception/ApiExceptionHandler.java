package com.dawn.ai.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

import java.net.HttpRetryException;
import java.time.OffsetDateTime;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AiConfigurationException.class)
    public ResponseEntity<Map<String, Object>> handleAiConfiguration(
            AiConfigurationException exception,
            HttpServletRequest request) {
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, Object>> handleRestClient(
            RestClientException exception,
            HttpServletRequest request) {
        String message = "AI provider request failed. Verify OPENAI_API_KEY and outbound network access.";
        Throwable cause = exception.getCause();
        if (cause instanceof HttpRetryException) {
            message = "AI provider authentication failed. Verify OPENAI_API_KEY before retrying.";
        }

        return buildResponse(HttpStatus.BAD_GATEWAY, message, request.getRequestURI());
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message, String path) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", OffsetDateTime.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message,
                "path", path
        ));
    }
}