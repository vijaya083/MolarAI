package com.molarai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record KnowledgeSearchRequest(
        @NotBlank String query,
        @NotNull @Min(1) @Max(10) Integer topK) {
}
