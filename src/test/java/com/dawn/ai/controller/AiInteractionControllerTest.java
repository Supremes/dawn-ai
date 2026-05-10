package com.dawn.ai.controller;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.springframework.http.MediaType.TEXT_HTML;
import static org.springframework.http.MediaType.TEXT_PLAIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AiInteractionController.class)
@Import(AiInteractionControllerTest.MeterConfig.class)
@TestPropertySource(properties = "app.ai.interaction-log.path=target/test-logs/ai-interaction-controller.log")
class AiInteractionControllerTest {

    private static final Path LOG_FILE = Paths.get("target/test-logs/ai-interaction-controller.log");

    @TestConfiguration
    static class MeterConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void cleanup() throws IOException {
        Files.deleteIfExists(LOG_FILE);
    }

    @Test
    void shouldReturnRawLogAsPlainTextByDefault() throws Exception {
        writeLogLines("""
                {"timestamp":"2026-05-09T21:00:00Z","sessionId":"session-1","direction":"response","body":"{\\"foo\\":1,\\"bar\\":true}"}
                """);

        mockMvc.perform(get("/api/v1/ai-interactions/log"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(TEXT_PLAIN))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"body\":\"{\\\"foo\\\":1,\\\"bar\\\":true}\"")));
    }

    @Test
    void shouldReturnPrettyPrintedHtmlWhenRequested() throws Exception {
        writeLogLines(
                """
                {"timestamp":"2026-05-09T21:00:00Z","sessionId":"session-1","direction":"response","body":"{\\"foo\\":1,\\"bar\\":{\\"baz\\":true}}"}
                {"timestamp":"2026-05-09T21:01:00Z","sessionId":"session-2","direction":"response","body":"plain text"}
                """
        );

        mockMvc.perform(get("/api/v1/ai-interactions/log")
                        .param("pretty", "true")
                        .param("sessionId", "session-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<pre class=\"log-output\">")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&quot;sessionId&quot; : &quot;session-1&quot;")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&quot;body&quot; : {")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&quot;foo&quot; : 1")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("session-2"))));
    }

    private void writeLogLines(String content) throws IOException {
        Files.createDirectories(LOG_FILE.getParent());
        Files.writeString(LOG_FILE, content.strip() + System.lineSeparator());
    }
}
