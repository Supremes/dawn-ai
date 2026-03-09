package com.dawn.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RagRequest {

    @NotBlank
    private String content;

    private String source;

    private String category;
}
