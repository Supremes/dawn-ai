package com.dawn.ai.exception;

import com.dawn.ai.controller.ChatController;
import com.dawn.ai.controller.RagController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerScopeTest {

    @Test
    void shouldOnlyApplyToBusinessControllers() {
        RestControllerAdvice advice = ApiExceptionHandler.class.getAnnotation(RestControllerAdvice.class);

        assertThat(advice).isNotNull();
        assertThat(advice.basePackageClasses())
                .containsExactlyInAnyOrder(ChatController.class, RagController.class);
    }
}
