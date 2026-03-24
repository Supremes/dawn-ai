package com.dawn.ai.controller;

import com.dawn.ai.exception.ApiExceptionHandler;
import com.dawn.ai.service.RagService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.util.Collections;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagController.class)
@Import({ApiExceptionHandler.class, RagControllerValidationTest.ValidationConfig.class})
class RagControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagService ragService;

    @Test
    void shouldRejectTopKGreaterThanTwenty() throws Exception {
        mockMvc.perform(get("/api/v1/rag/search")
                        .param("query", "refund policy")
                        .param("topK", "21"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("must be less than or equal to 20")));
    }

    @Test
    void shouldAllowTopKWithinLimit() throws Exception {
        when(ragService.retrieve("refund policy", 20)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/rag/search")
                        .param("query", "refund policy")
                        .param("topK", "20"))
                .andExpect(status().isOk());

        verify(ragService).retrieve("refund policy", 20);
    }

    @TestConfiguration
    static class ValidationConfig {
        @Bean
        MethodValidationPostProcessor methodValidationPostProcessor() {
            return new MethodValidationPostProcessor();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
