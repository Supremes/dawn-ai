package com.dawn.ai.controller;

import com.dawn.ai.exception.ApiExceptionHandler;
import com.dawn.ai.rag.TopicQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TopicController.class)
@Import({ApiExceptionHandler.class, TopicControllerTest.MeterConfig.class})
class TopicControllerTest {

    @TestConfiguration
    static class MeterConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TopicQueryService topicQueryService;

    @Test
    void listTopics_returnsTopicsFromService() throws Exception {
        when(topicQueryService.listTopics()).thenReturn(List.of("distributed-tx", "spring-boot"));

        mockMvc.perform(get("/api/v1/topics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.topics").isArray())
            .andExpect(jsonPath("$.topics[0]").value("distributed-tx"))
            .andExpect(jsonPath("$.topics[1]").value("spring-boot"));
    }

    @Test
    void listTopics_returnsEmptyListWhenNoTopics() throws Exception {
        when(topicQueryService.listTopics()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/topics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.topics").isEmpty());
    }
}
