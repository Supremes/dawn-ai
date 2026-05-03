package com.dawn.ai.controller;

import com.dawn.ai.exception.ApiExceptionHandler;
import com.dawn.ai.rag.RagService;
import com.dawn.ai.rag.constants.DocumentType;
import com.dawn.ai.rag.ingestion.DocumentTextExtractor;
import com.dawn.ai.rag.retrieval.RetrievalRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagController.class)
@Import({ApiExceptionHandler.class, RagControllerValidationTest.ValidationConfig.class})
class RagControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagService ragService;

    @MockBean
    private DocumentTextExtractor documentTextExtractor;

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
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/rag/search")
                        .param("query", "refund policy")
                        .param("topK", "20"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<RetrievalRequest> captor =
                org.mockito.ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(ragService).retrieve(captor.capture());
        assertThat(captor.getValue().getQuery()).isEqualTo("refund policy");
        assertThat(captor.getValue().getTopK()).isEqualTo(20);
        assertThat(captor.getValue().getMetadataFilters()).isEmpty();
    }

    @Test
    void shouldPassMetadataFiltersToRagService() throws Exception {
        when(ragService.retrieve(any(RetrievalRequest.class))).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/rag/search")
                        .param("query", "refund policy")
                        .param("topK", "5")
                        .param("source", "pricing-doc")
                        .param("category", "billing"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<RetrievalRequest> captor =
                org.mockito.ArgumentCaptor.forClass(RetrievalRequest.class);
        verify(ragService).retrieve(captor.capture());
        assertThat(captor.getValue().getQuery()).isEqualTo("refund policy");
        assertThat(captor.getValue().getMetadataFilters()).containsEntry("source", List.of("pricing-doc"));
        assertThat(captor.getValue().getMetadataFilters()).containsEntry("category", List.of("billing"));
    }

        @Test
        void shouldIngestMultipartFileWithInferredTextType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "faq.txt",
            "text/plain",
            "refund policy content".getBytes()
        );
        when(documentTextExtractor.extract(any(), eq(DocumentType.TEXT))).thenReturn("refund policy content");
        when(ragService.ingest("refund policy content", "faq.txt", "billing")).thenReturn("doc-123");

        mockMvc.perform(multipart("/api/v1/rag/ingest")
                .file(file)
                .param("category", "billing"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.docId").value("doc-123"))
            .andExpect(jsonPath("$.status").value("ingested"))
            .andExpect(jsonPath("$.documentType").value("TEXT"));

        verify(documentTextExtractor).extract(any(), eq(DocumentType.TEXT));
        verify(ragService).ingest("refund policy content", "faq.txt", "billing");
        }

        @Test
        void shouldRejectUnsupportedMultipartFileType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "data.bin",
            "application/octet-stream",
            "random".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/rag/ingest").file(file))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("Unsupported file format")));

        verifyNoInteractions(documentTextExtractor);
        verifyNoInteractions(ragService);
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
