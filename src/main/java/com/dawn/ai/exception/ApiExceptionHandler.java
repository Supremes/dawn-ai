package com.dawn.ai.exception;

import com.dawn.ai.controller.ChatController;
import com.dawn.ai.controller.RagController;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
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

@RestControllerAdvice(basePackageClasses = {ChatController.class, RagController.class})
@RequiredArgsConstructor
public class ApiExceptionHandler {

    private static final String LLM_UNAVAILABLE_MESSAGE =
            "AI provider is temporarily unavailable (circuit breaker open after repeated upstream failures). Please retry shortly.";

    private final MeterRegistry meterRegistry;

    /**
     * LLM 熔断器 OPEN：请求在发起网络调用前就被 resilience4j 拒绝，返回 503 让客户端稍后重试。
     */
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<Map<String, Object>> handleCircuitOpen(
            CallNotPermittedException exception,
            HttpServletRequest request) {
        recordError("llm_circuit_open");
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, LLM_UNAVAILABLE_MESSAGE, request.getRequestURI());
    }

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
        CallNotPermittedException open = findCause(exception, CallNotPermittedException.class);
        if (open != null) {
            return handleCircuitOpen(open, request);
        }
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException exception,
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

    @ExceptionHandler(MaxStepsExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxStepsExceeded(
            MaxStepsExceededException exception,
            HttpServletRequest request
    ) {
        recordError("max_steps_exceeded_error");
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(LLMProviderException.class)
    public ResponseEntity<Map<String, Object>> handleLLMProviderException(
            LLMProviderException exception,
            HttpServletRequest request
    ) {
        recordError("llm_provider_error");
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        CallNotPermittedException open = findCause(exception, CallNotPermittedException.class);
        if (open != null) {
            return handleCircuitOpen(open, request);
        }
        recordError("internal_error");
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", request.getRequestURI());
    }

    /**
     * 沿 cause 链查找指定类型的异常（防自环 / 深度上限），找不到返回 null。
     */
    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 32) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return null;
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
