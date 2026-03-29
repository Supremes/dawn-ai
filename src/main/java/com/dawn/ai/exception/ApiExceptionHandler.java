package com.dawn.ai.exception;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.net.HttpRetryException;
import java.time.OffsetDateTime;
import java.util.Map;

@RestControllerAdvice
@RequiredArgsConstructor
public class ApiExceptionHandler {

    private final MeterRegistry meterRegistry;

    @ExceptionHandler(AiConfigurationException.class)
    public ResponseEntity<Map<String, Object>> handleAiConfiguration(
            AiConfigurationException exception,
            HttpServletRequest request) {
        recordError("config_error");
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, Object>> handleRestClient(
            RestClientException exception,
            HttpServletRequest request) {
        String message = "AI provider request failed. Verify DEEPSEEK_API_KEY/OPENAI_API_KEY, base URL, and outbound network access.";
        Throwable cause = exception.getCause();
        if (cause instanceof HttpRetryException) {
            message = "AI provider authentication failed. Verify DEEPSEEK_API_KEY/OPENAI_API_KEY and spring.ai.openai.base-url before retrying.";
            recordError("llm_auth_error");
        } else {
            recordError("llm_error");
        }
        return buildResponse(HttpStatus.BAD_GATEWAY, message, request.getRequestURI());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request) {
        recordError("validation_error");
        return buildResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        recordError("validation_error");
        return buildResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(PlanGenerationException.class)
    public ResponseEntity<Map<String, Object>> handlePlanGeneration(
            PlanGenerationException exception,
            HttpServletRequest request) {
        recordError("plan_generation_error");
        return buildResponse(HttpStatus.BAD_GATEWAY, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        recordError("internal_error");
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", request.getRequestURI());
    }

    private void recordError(String type) {
        meterRegistry.counter("ai.error.total", "type", type).increment();
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
